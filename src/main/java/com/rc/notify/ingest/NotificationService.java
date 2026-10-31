package com.rc.notify.ingest;

import com.rc.notify.api.ApiException;
import com.rc.notify.config.AuthProperties;
import com.rc.notify.config.NotifyProperties;
import com.rc.notify.ingest.dto.SubmitNotificationRequest;
import com.rc.notify.ingest.dto.SubmitNotificationResponse;
import com.rc.notify.store.NotificationRepository;
import com.rc.notify.store.PayloadCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * 提交通知用例（T1.1）：业务校验 → 编码 → 落库 → 组装响应。
 *
 * <p>校验顺序：结构校验（Bean Validation，在 Controller 层）→ 这里的业务校验 → 落库。
 * 业务校验集中在一个方法里，避免"能不能提交"的判断散落在 Controller / Repository。
 */
@Service
public class NotificationService {

    /** HTTP 方法白名单：只允许真正有语义的方法，防方法注入。 */
    private static final Set<String> ALLOWED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE", "GET");

    /** 与 headers_enc VARBINARY(4096) 对齐。 */
    private static final int MAX_HEADERS_BYTES = 4096;
    private static final int MAX_HEADER_COUNT = 50;
    /** 64KB 上限：业务方已渲染好的请求体，超过这个量级说明用错了系统。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final NotificationRepository repository;
    private final PayloadCodec codec;
    private final NotifyProperties props;

    public NotificationService(NotificationRepository repository, PayloadCodec codec, NotifyProperties props) {
        this.repository = repository;
        this.codec = codec;
        this.props = props;
    }

    public SubmitNotificationResponse submit(SubmitNotificationRequest req, AuthProperties.Client caller) {
        String targetUrl = validateTargetUrl(req.targetUrl());
        String method = validateMethod(req.httpMethod());
        validateHeaders(req.headers());
        validateBody(req.body());

        byte[] headers = codec.encodeHeaders(req.headers());
        byte[] body = codec.encodeBody(req.body());
        if (headers != null && headers.length > MAX_HEADERS_BYTES) {
            // 编码后仍可能超（JSON 转义），在落库前拦住而不是让数据库报错
            throw ApiException.validation("headers 序列化后不能超过 4096 字节");
        }

        int maxAttempts = caller.maxAttempts() != null
                ? caller.maxAttempts()
                : props.retry().maxAttempts();

        String idempotencyKey = req.idempotencyKey().trim();

        try {
            long id = repository.insert(new NotificationRepository.NewNotification(
                    caller.callerId(), idempotencyKey, targetUrl, method,
                    headers, body, maxAttempts));
            return new SubmitNotificationResponse(id, "PENDING");
        } catch (DuplicateKeyException e) {
            // 幂等命中（T1.3）：调用方超时重试是正常路径，不是错误路径——返回 202 + 原单，
            // 让重试与首次提交在调用方看来完全一致（T1.3 文档 §2.2）。
            // 唯一键冲突时原单必然已提交（InnoDB 插入会等待未提交的冲突键），回查不应读空。
            return repository.findExisting(caller.callerId(), idempotencyKey)
                    .map(existing -> new SubmitNotificationResponse(existing.id(), existing.status()))
                    .orElseThrow(() -> new IllegalStateException(
                            "唯一键冲突但回查不到原单，caller=%s, key=%s".formatted(caller.callerId(), idempotencyKey)));
        }
    }

    // ——— 业务校验：失败一律 422，message 指向具体字段 ———

    private String validateTargetUrl(String raw) {
        String url = raw.trim();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw ApiException.validation("target_url 不是合法 URL: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw ApiException.validation("target_url 只支持 http/https，当前: " + scheme);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw ApiException.validation("target_url 缺少 host");
        }
        // SSRF 白名单与私有 IP 拦截属 T2.4，此处只做协议与结构校验
        return url;
    }

    private String validateMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return "POST";
        }
        String method = raw.trim().toUpperCase();
        if (!ALLOWED_METHODS.contains(method)) {
            throw ApiException.validation("http_method 只支持 " + ALLOWED_METHODS + "，当前: " + raw);
        }
        return method;
    }

    private void validateHeaders(Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        if (headers.size() > MAX_HEADER_COUNT) {
            throw ApiException.validation("headers 不能超过 %d 项".formatted(MAX_HEADER_COUNT));
        }
        headers.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                throw ApiException.validation("headers 存在空名称");
            }
            if (containsCrlf(name) || containsCrlf(value)) {
                throw ApiException.validation("headers 的 " + name + " 含非法字符（CR/LF）");
            }
        });
    }

    private void validateBody(String body) {
        if (body == null) {
            return;
        }
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BODY_BYTES) {
            throw ApiException.validation("body 不能超过 %d 字节，当前 %d".formatted(MAX_BODY_BYTES, bytes));
        }
    }

    private static boolean containsCrlf(String s) {
        return s != null && (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0);
    }
}
