package com.rc.notify.ingest;

import com.rc.notify.metrics.QueueSampler;
import com.rc.notify.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3.3 背压集成验收（真实 MySQL）：走完整受理链路，不用 mock 替换判定。
 *
 * <p>造一条"31 分钟前就该被投、但因为调度器关着一直没人投"的 PENDING 行——这正好是队列不健康的
 * 真实形态（到点了没人处理），而不是人为把阈值调成 0。用默认配置跑，阈值语义与线上一致。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false"
})
@AutoConfigureMockMvc
class BackpressureIntegrationTest extends AbstractIntegrationTest {

    private static final String BODY = """
            {"idempotency_key":"order_paid:bp_1","target_url":"https://crm.example.com/cb","body":"{}"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** 采样器由测试手动驱动：调度器关着，采样也必须可控，否则断言依赖时序。 */
    @Autowired
    private QueueSampler sampler;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");
    }

    @Test
    void 队列积压超阈值时新提交返回503并带RetryAfter且不落库() throws Exception {
        insertOverdue("old:1", 31);

        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.code").value("QUEUE_OVERLOADED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("队列积压")));

        // 背压是"受理前拒绝"：库里只应该有预置的那一条，没有新行——这正是它比"收下再说"诚实的地方
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).isEqualTo(1);
    }

    /**
     * 关键回归：处于退避中的任务不该触发背压。
     *
     * <p>如果"最老任务年龄"用 {@code created_at} 算，一条 30 小时前创建、正在 1 小时后重试的单
     * （最长退避曲线本来就有 3.5h）会让系统误判为积压，把整个受理入口关掉——
     * 一个健康的系统因为"有任务正在正常等待重试"而拒绝所有新流量。
     */
    @Test
    void 退避中的老任务不触发背压() throws Exception {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, status, attempt_count, max_attempts,
                     next_attempt_at, created_at)
                VALUES ('shop', 'retrying:1', 'https://crm.example.com/cb', 'POST', 'RETRY_WAIT', 3, 10,
                        DATE_ADD(NOW(3), INTERVAL 1 HOUR), DATE_SUB(NOW(3), INTERVAL 30 HOUR))
                """);
        sampler.sample();

        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());
    }

    @Test
    void 队列健康时正常受理() throws Exception {
        sampler.sample();

        mockMvc.perform(post("/v1/notifications")
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, "k_shop")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    /** 造一条"到点了但还没被投"的 PENDING 行：逾期 minutes 分钟。 */
    private void insertOverdue(String key, int minutes) {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, status, attempt_count, max_attempts,
                     next_attempt_at)
                VALUES ('shop', ?, 'https://crm.example.com/cb', 'POST', 'PENDING', 0, 10,
                        DATE_SUB(NOW(3), INTERVAL ? MINUTE))
                """, key, minutes);
        // 采样一次，让背压看到这份积压（生产里是每 10s 自动采样）
        sampler.sample();
    }
}
