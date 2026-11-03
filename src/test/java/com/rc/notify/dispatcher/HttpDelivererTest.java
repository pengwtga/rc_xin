package com.rc.notify.dispatcher;

import com.rc.notify.config.NotifyProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpDeliverer 单元测试：本地 HttpServer 走真 HTTP，不依赖 DB、不经过调度器。
 * total timeout 用 300ms 短超时验证 TIMEOUT 分类（不为测试改生产默认值）。
 */
class HttpDelivererTest {

    private HttpServer server;
    private final CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

    private static NotifyProperties props(long totalTimeoutMs) {
        return new NotifyProperties(
                new NotifyProperties.Dispatcher(true, 500, 100, 8, 60_000),
                new NotifyProperties.Http(1000, totalTimeoutMs),
                new NotifyProperties.Backoff(10_000, 3_600_000, 0.5),
                new NotifyProperties.Retry(10),
                new NotifyProperties.Backpressure(100_000, 30, 30));
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " nid=" + exchange.getRequestHeaders().getFirst("X-Notification-Id")
                    + " idem=" + exchange.getRequestHeaders().getFirst("X-Idempotency-Key")
                    + " nidAll=" + exchange.getRequestHeaders().get("X-Notification-Id")
                    + " body=" + new String(reqBody));
            byte[] resp = "{\"ok\":true}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 二百成功且请求头请求体完整到达() {
        HttpDeliverer d = new HttpDeliverer(props(15_000));
        HttpDeliverer.Outcome o = d.deliver(new HttpDeliverer.Target(
                "POST", "http://127.0.0.1:" + server.getAddress().getPort() + "/cb",
                Map.of("Authorization", "Bearer tok", "Content-Type", "application/json"),
                "{\"x\":1}".getBytes()));

        assertThat(o.isSuccess()).isTrue();
        assertThat(o.statusCode()).isEqualTo(200);
        assertThat(o.errorClass()).isNull();
        assertThat(o.responseSnippet()).contains("ok");
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("POST /cb", "auth=Bearer tok", "body={\"x\":1}");
    }

    @Test
    void 四百响应透传状态码() {
        server.createContext("/400", exchange -> {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        HttpDeliverer d = new HttpDeliverer(props(15_000));
        HttpDeliverer.Outcome o = d.deliver(new HttpDeliverer.Target(
                "POST", "http://127.0.0.1:" + server.getAddress().getPort() + "/400", Map.of(), null));
        assertThat(o.statusCode()).isEqualTo(400);
        assertThat(o.errorClass()).isNull();
        assertThat(o.isSuccess()).isFalse();
    }

    @Test
    void 超时折叠为TIMEOUT分类() {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        HttpDeliverer d = new HttpDeliverer(props(300));
        HttpDeliverer.Outcome o = d.deliver(new HttpDeliverer.Target(
                "POST", "http://127.0.0.1:" + server.getAddress().getPort() + "/slow", Map.of(), null));
        assertThat(o.statusCode()).isNull();
        assertThat(o.errorClass()).isEqualTo("TIMEOUT");
    }

    @Test
    void 三百零二不跟随_重定向防护从投递器出生就有() {
        server.createContext("/redir", exchange -> {
            exchange.getResponseHeaders().add("Location", "/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        HttpDeliverer d = new HttpDeliverer(props(15_000));
        HttpDeliverer.Outcome o = d.deliver(new HttpDeliverer.Target(
                "POST", "http://127.0.0.1:" + server.getAddress().getPort() + "/redir", Map.of(), null));
        assertThat(o.statusCode()).isEqualTo(302);
        assertThat(received).noneMatch(s -> s.contains("/secret"));
    }

    @Test
    void 连接拒绝折叠为CONN_REFUSED() {
        // 占一个端口然后立刻释放，连接它几乎必然被拒
        int deadPort;
        try (var s = new java.net.ServerSocket(0)) {
            deadPort = s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        HttpDeliverer d = new HttpDeliverer(props(15_000));
        HttpDeliverer.Outcome o = d.deliver(new HttpDeliverer.Target(
                "POST", "http://127.0.0.1:" + deadPort + "/cb", Map.of(), null));
        assertThat(o.errorClass()).isEqualTo("CONN_REFUSED");
    }

    @Test
    void 非法URL按IO兜底而不抛异常() {
        HttpDeliverer d = new HttpDeliverer(props(15_000));
        HttpDeliverer.Outcome o = d.deliver(new HttpDeliverer.Target(
                "POST", "::not-a-url::", Map.of(), null));
        assertThat(o.errorClass()).isEqualTo("IO");
    }

    @Test
    void 身份头注入_调用方同名头被取代且只有一个值() {
        HttpDeliverer d = new HttpDeliverer(props(15_000));
        HttpDeliverer.Outcome o = d.deliver(new HttpDeliverer.Target(
                "POST", "http://127.0.0.1:" + server.getAddress().getPort() + "/cb",
                // 调用方自带 X-Notification-Id：必须被取代，而不是拼出两个值让下游无法去重
                Map.of("X-Notification-Id", "caller-forged", "X-Idempotency-Key", "caller-key"),
                "{\"x\":1}".getBytes(),
                42L, "order_paid:pay_001"));

        assertThat(o.isSuccess()).isTrue();
        assertThat(received).hasSize(1);
        String r = received.get(0);
        assertThat(r).contains("nid=42", "idem=order_paid:pay_001");
        assertThat(r).as("调用方伪造的同名头不得残留").doesNotContain("caller-forged");
        assertThat(r).as("每个身份头只有一个值（getFirst 与整个 list 一致）")
                .contains("nidAll=[42]");
    }

    @Test
    void 无身份场景不注入身份头() {
        HttpDeliverer d = new HttpDeliverer(props(15_000));
        d.deliver(new HttpDeliverer.Target(
                "POST", "http://127.0.0.1:" + server.getAddress().getPort() + "/cb", Map.of(), null));
        assertThat(received.get(0)).contains("nid=null", "idem=null", "nidAll=null");
    }

    @Test
    void RetryAfter只解析数值秒_其余一律null() {
        // 数值格式：解析成功（RFC 7231 的主流实现形态）
        assertThat(HttpDeliverer.parseRetryAfterSeconds("120")).isEqualTo(120L);
        assertThat(HttpDeliverer.parseRetryAfterSeconds(" 30 ")).isEqualTo(30L);
        assertThat(HttpDeliverer.parseRetryAfterSeconds("0")).isZero();

        // HTTP-date 格式：不支持（需应用时钟解释"现在"，与 §4.3 DB 时钟纪律冲突，T2.1 文档 §2.4）
        assertThat(HttpDeliverer.parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();

        // 缺失 / 空白 / 垃圾 / 负数：静默忽略，绝不让解析失败影响投递结果
        assertThat(HttpDeliverer.parseRetryAfterSeconds(null)).isNull();
        assertThat(HttpDeliverer.parseRetryAfterSeconds("")).isNull();
        assertThat(HttpDeliverer.parseRetryAfterSeconds("abc")).isNull();
        assertThat(HttpDeliverer.parseRetryAfterSeconds("-5")).isNull();
    }
}
