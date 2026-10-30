package com.rc.notify.dispatcher;

import com.rc.notify.config.NotifyProperties;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * 单次出站 HTTP 投递（T1.2）。无状态、无业务语义——只回答"这次请求结果如何"。
 *
 * <p>三个不可妥协的配置（T1.2 文档 §2.7 及设计文档 §3.3）：
 * <ul>
 *   <li>connect 3s / total 15s：防御型超时，按对端 P99 定值（参数定值依据 §3.2）；
 *       方向代价不对称——worker 是稀缺资源，宁可误杀重试，不让 worker 挂着；</li>
 *   <li>禁重定向（{@link HttpClient.Redirect#NEVER}）：跟随 302 会把带凭证的请求
 *       转发到 Location 指向的任意主机，HttpClient 默认 NORMAL 恰好跟随——
 *       该偏差必须在投递器出生那天钉死，T2.4 SSRF 校验之前这是唯一防线；</li>
 *   <li>响应只读前 2048 字节作为排查片段：无界读 body 会让一个失控的对端拖住 worker。</li>
 * </ul>
 *
 * <p>本类**不抛异常**：网络层失败一律折叠为 errorClass（TIMEOUT/DNS/TLS/CONN_REFUSED/IO），
 * 分类到重试策略的翻译由上层（T2.1 ErrorClassifier）负责。
 */
@Component
public class HttpDeliverer {

    /** 响应片段上限，与 delivery_attempt.response_snippet 列宽一致。 */
    static final int SNIPPET_LIMIT = 2048;

    private final HttpClient client;
    private final long totalTimeoutMs;

    public HttpDeliverer(NotifyProperties props) {
        NotifyProperties.Http h = props.http();
        this.totalTimeoutMs = h.totalTimeoutMs();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(h.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)   // §2.7：禁重定向，永不放宽
                .build();
    }

    /** 单次投递结果。statusCode 与 errorClass 互斥：HTTP 有响应则前者非空，网络层失败则后者非空。 */
    public record Outcome(Integer statusCode, String errorClass, long durationMs, String responseSnippet) {

        public boolean isSuccess() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }
    }

    public record Target(String method, String url, Map<String, String> headers, byte[] body) {}

    /**
     * 执行投递。任何网络层异常折叠为 Outcome（errorClass 非空）；程序性错误（URI 非法等）同样折叠，
     * 绝不向上抛——投递线程的存活比单条任务的结果更重要（异常边界见 T1.2 文档 §4）。
     */
    public Outcome deliver(Target t) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(t.url()))
                    .timeout(Duration.ofMillis(totalTimeoutMs))
                    .header("User-Agent", "rc-notify/0.1");
            t.headers().forEach((k, v) -> {
                if (!isRestrictedHeader(k)) {
                    rb.header(k, v);
                }
            });
            if (t.body() != null) {
                rb.method(t.method(), HttpRequest.BodyPublishers.ofByteArray(t.body()));
            } else {
                rb.method(t.method(), HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<java.io.InputStream> resp =
                    client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            String snippet = readSnippet(resp.body());
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new Outcome(resp.statusCode(), null, ms, snippet);
        } catch (HttpTimeoutException e) {
            return fail(start, "TIMEOUT");
        } catch (UnknownHostException e) {
            return fail(start, "DNS");
        } catch (SSLException e) {
            return fail(start, "TLS");
        } catch (ConnectException e) {
            return fail(start, "CONN_REFUSED");
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException：URI 非法 / method 白名单外等程序性错误，按 IO 失败兜底
            return fail(start, "IO");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(start, "IO");
        }
    }

    /** HttpClient 禁改的安全敏感头（JDK 会抛 IllegalArgumentException，这里显式跳过并保持行为可预期）。 */
    private static boolean isRestrictedHeader(String name) {
        String n = name.toLowerCase();
        return n.equals("host") || n.equals("content-length") || n.equals("connection");
    }

    private static Outcome fail(long startNanos, String errorClass) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        return new Outcome(null, errorClass, ms, null);
    }

    /** 只读前 SNIPPET_LIMIT 字节并关闭流——无界读会让失控对端拖住 worker。 */
    private static String readSnippet(java.io.InputStream in) throws UncheckedIOException {
        try (in) {
            byte[] buf = in.readNBytes(SNIPPET_LIMIT);
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 片段读取失败不影响投递结果本身
            return null;
        }
    }
}
