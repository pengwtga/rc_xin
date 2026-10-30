package com.rc.notify.ingest;

import com.rc.notify.config.AuthProperties;
import com.rc.notify.config.NotifyProperties;
import com.rc.notify.ingest.dto.SubmitNotificationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1.1 控制器层验收：鉴权 / 结构校验 / 202 语义。
 * 不起数据库（Service 被 mock），保证 mvn test 在任何机器上都绿。
 */
@WebMvcTest(IngestController.class)
@Import({ApiKeyRegistry.class, ApiKeyAuthFilter.class})
@org.springframework.boot.context.properties.EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = {
        "notify.auth.clients[0].api-key=k_test",
        "notify.auth.clients[0].caller-id=shop"
})
class IngestControllerTest {

    private static final String VALID_BODY = """
            {"idempotency_key":"order_paid:pay_001","target_url":"https://crm.example.com/cb","body":"{}"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService service;

    /** 切片测试不加载主应用，这里补上 Service 需要的 NotifyProperties。 */
    @TestConfiguration
    static class PropsConfig {
        @Bean
        NotifyProperties notifyProperties() {
            return new NotifyProperties(
                    new NotifyProperties.Dispatcher(true, 500, 100, 200, 60_000),
                    new NotifyProperties.Http(3_000, 15_000),
                    new NotifyProperties.Backoff(10_000, 3_600_000, 0.5),
                    new NotifyProperties.Retry(10),
                    new NotifyProperties.Backpressure(100_000, 30));
        }
    }

    @Test
    void 缺少ApiKey返回401且不进入业务层() throws Exception {
        mockMvc.perform(post("/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void ApiKey无效返回401() throws Exception {
        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 缺targetUrl返回422而不是Spring默认400() throws Exception {
        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotency_key\":\"a:1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("target_url 不能为空"));
    }

    @Test
    void 非法JSON返回422() throws Exception {
        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_test")
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 合法请求返回202且把凭证派生的caller传给业务层() throws Exception {
        given(service.submit(any(), any())).willReturn(new SubmitNotificationResponse(101, "PENDING"));

        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_test")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notification_no").value(101))
                .andExpect(jsonPath("$.status").value("PENDING"));

        var captor = org.mockito.ArgumentCaptor.forClass(AuthProperties.Client.class);
        org.mockito.Mockito.verify(service).submit(any(), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("shop", captor.getValue().callerId());
    }
}
