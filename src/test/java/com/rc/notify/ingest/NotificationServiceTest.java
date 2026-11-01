package com.rc.notify.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notify.api.ApiException;
import com.rc.notify.config.AuthProperties;
import com.rc.notify.config.CryptoProperties;
import com.rc.notify.config.NotifyProperties;
import com.rc.notify.config.SsrfProperties;
import com.rc.notify.ingest.dto.SubmitNotificationRequest;
import com.rc.notify.security.SsrfGuard;
import com.rc.notify.store.AesGcmPayloadCodec;
import com.rc.notify.store.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T1.1 业务校验单元测试：URL 协议 / method 白名单 / Header CRLF 与体积 / body 体积。
 * 不依赖 Spring 与数据库。
 */
class NotificationServiceTest {

    /** 与 application.yml 默认值一致的开发密钥（base64 的 32 字节）。 */
    private static final String DEV_KEY = "ZGV2LW9ubHktMzJieXRlLWtleS1kby1ub3QtdXNlISE=";

    private NotificationRepository repository;
    private NotificationService service;
    private final AuthProperties.Client caller = new AuthProperties.Client("k_shop", "shop", null);

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        var props = new NotifyProperties(
                new NotifyProperties.Dispatcher(true, 500, 100, 200, 60_000),
                new NotifyProperties.Http(3_000, 15_000),
                new NotifyProperties.Backoff(10_000, 3_600_000, 0.5),
                new NotifyProperties.Retry(10),
                new NotifyProperties.Backpressure(100_000, 30));
        service = new NotificationService(repository,
                new AesGcmPayloadCodec(new CryptoProperties(DEV_KEY)),
                props,
                new SsrfGuard(new SsrfProperties(List.of(), true)));
    }

    private SubmitNotificationRequest req(String url, String method, Map<String, String> headers, String body) {
        return new SubmitNotificationRequest("order_paid:pay_001", url, method, headers, body);
    }

    @Test
    void 非http协议被拒绝_422() {
        assertThatThrownBy(() -> service.submit(req("ftp://x.com/cb", null, null, null), caller))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http/https");
    }

    @Test
    void 缺少host被拒绝_422() {
        assertThatThrownBy(() -> service.submit(req("https:///path", null, null, null), caller))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("host");
    }

    @Test
    void 非白名单method被拒绝_422() {
        assertThatThrownBy(() -> service.submit(req("https://x.com/cb", "TRACE", null, null), caller))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http_method");
    }

    @Test
    void header含CRLF被拒绝_防Header注入() {
        assertThatThrownBy(() -> service.submit(
                req("https://x.com/cb", null, Map.of("X-Evil", "a\r\nX-Injected: 1"), null), caller))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CR/LF");
    }

    @Test
    void header超过50项被拒绝() {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < 51; i++) {
            headers.put("X-H" + i, "v");
        }
        assertThatThrownBy(() -> service.submit(req("https://x.com/cb", null, headers, null), caller))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("50");
    }

    @Test
    void body超过64KB被拒绝() {
        String big = "x".repeat(64 * 1024 + 1);
        assertThatThrownBy(() -> service.submit(req("https://x.com/cb", null, null, big), caller))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("body");
    }

    @Test
    void 合法请求落库_默认method为POST且重试预算取全局值() {
        given(repository.insert(any())).willReturn(42L);

        var resp = service.submit(req(" https://x.com/cb ", null, Map.of("X-A", "1"), "{}"), caller);

        assertThat(resp.notificationNo()).isEqualTo(42L);
        assertThat(resp.status()).isEqualTo("PENDING");

        var captor = ArgumentCaptor.forClass(NotificationRepository.NewNotification.class);
        verify(repository).insert(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.callerId()).isEqualTo("shop");
        assertThat(saved.targetUrl()).isEqualTo("https://x.com/cb");
        assertThat(saved.httpMethod()).isEqualTo("POST");
        assertThat(saved.maxAttempts()).isEqualTo(10);
        assertThat(saved.headers()).isNotNull();
        assertThat(saved.body()).isNotNull();
    }

    @Test
    void caller覆盖重试预算时生效() {
        given(repository.insert(any())).willReturn(7L);
        var custom = new AuthProperties.Client("k_inventory", "inventory", 6);

        service.submit(req("https://x.com/cb", "PUT", null, null), custom);

        var captor = ArgumentCaptor.forClass(NotificationRepository.NewNotification.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().maxAttempts()).isEqualTo(6);
        assertThat(captor.getValue().httpMethod()).isEqualTo("PUT");
    }

    @Test
    void 幂等命中_返回原单号与原单当前状态() {
        given(repository.insert(any())).willThrow(
                new org.springframework.dao.DuplicateKeyException("uk_idem dup"));
        given(repository.findExisting("shop", "order_paid:pay_001"))
                .willReturn(java.util.Optional.of(new NotificationRepository.Existing(42L, "SUCCEEDED")));

        var resp = service.submit(req("https://x.com/cb", null, null, "{}"), caller);

        assertThat(resp.notificationNo()).isEqualTo(42L);
        assertThat(resp.status()).isEqualTo("SUCCEEDED");   // 快照语义：原单真实状态，不是 PENDING
    }

    @Test
    void 幂等键提交前trim_回查用同一规范化值() {
        given(repository.insert(any())).willThrow(
                new org.springframework.dao.DuplicateKeyException("uk_idem dup"));
        given(repository.findExisting("shop", "order_paid:pay_001"))
                .willReturn(java.util.Optional.of(new NotificationRepository.Existing(9L, "PENDING")));

        var resp = service.submit(new SubmitNotificationRequest(
                "  order_paid:pay_001  ", "https://x.com/cb", null, null, "{}"), caller);

        assertThat(resp.notificationNo()).isEqualTo(9L);
    }

    @Test
    void 冲突但回查不到原单_抛异常而不是编造单号() {
        given(repository.insert(any())).willThrow(
                new org.springframework.dao.DuplicateKeyException("uk_idem dup"));
        given(repository.findExisting(any(), any())).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.submit(req("https://x.com/cb", null, null, "{}"), caller))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回查不到原单");
    }
}
