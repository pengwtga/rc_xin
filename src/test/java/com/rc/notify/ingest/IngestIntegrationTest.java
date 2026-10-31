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
    void 同幂等键重复提交返回202与原单号_表中仅一行() throws Exception {
        String first = mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())                      // 不是 409：重试是正常路径
                .andReturn().getResponse().getContentAsString();

        // 两次响应单号相同（JsonPath.value(Object) 会被编译器解析成 value(Matcher)，故显式取数比较）
        assertThat(noOf(second)).isEqualTo(noOf(first));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void 十并发同键提交_全部拿到同一单号且仅落一行() throws Exception {
        int threads = 10;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var startGate = new java.util.concurrent.CountDownLatch(1);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    var resp = mockMvc.perform(post("/v1/notifications")
                                    .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                                    .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                            .andExpect(status().isAccepted())
                            .andReturn().getResponse().getContentAsString();
                    results.add(noOf(resp));
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        startGate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        assertThat(results).hasSize(threads);
        assertThat(results.stream().distinct().count()).isEqualTo(1);   // 10 个响应同单号
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).isEqualTo(1);
    }

    @Test
    void 重复提交已成功的单_返回原单当前状态而非PENDING() throws Exception {
        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted());

        // 模拟原单已完成（T1.2 投递成功后的状态）
        jdbc.update("UPDATE notification SET status='SUCCEEDED' WHERE idempotency_key='order_paid:pay_it_001'");

        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));   // 快照：真实状态，不撒谎
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

    private static long noOf(String json) {
        return ((Number) com.jayway.jsonpath.JsonPath.read(json, "$.notification_no")).longValue();
    }
}
