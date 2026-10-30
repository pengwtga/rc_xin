package com.rc.notify.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1.1 集成验收（真实 MySQL）。
 *
 * <p>默认跳过，避免无数据库环境下 mvn test 变红；本地验证：
 * <pre>docker compose up -d &amp;&amp; MYSQL_IT=true mvn test</pre>
 * <p>调度器置 false（§9.2 用例 7）：防止后台线程把测试数据当真实流量投出去。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:33061/notify?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT", matches = "true")
class IngestIntegrationTest {

    private static final String VALID_BODY = """
            {"idempotency_key":"order_paid:pay_it_001","target_url":"https://crm.example.com/cb",
             "headers":{"Authorization":"Bearer t"},"body":"{\\"contact\\":1}"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");
    }

    @Test
    void 提交后落库为PENDING且到期时间不晚于数据库当前时间() throws Exception {
        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.notification_no").isNumber());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT caller_id, status, attempt_count, max_attempts, http_method, target_url, "
                        + "next_attempt_at <= NOW(3) AS due FROM notification");
        assertThat(row.get("caller_id")).isEqualTo("shop");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
        assertThat(((Number) row.get("max_attempts")).intValue()).isEqualTo(10);
        assertThat(row.get("http_method")).isEqualTo("POST");
        assertThat(((Number) row.get("due")).intValue()).isEqualTo(1);
    }

    @Test
    void 同幂等键重复提交返回409_本阶段语义_T13改为202原单号() throws Exception {
        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_IDEMPOTENCY_KEY"));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 不同caller使用相同幂等键互不影响() throws Exception {
        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_crm")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
