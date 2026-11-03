package com.rc.notify.metrics;

import com.rc.notify.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3.3 队列采样的数值验收（真实 MySQL）。
 *
 * <p>背压能不能正确触发全看这几个数，而它们最容易在"定义"上写错：
 * backlog 该不该算 PROCESSING、oldestOverdue 该不该算未来的任务——每一条都在下面钉住。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false"
})
class QueueSamplerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private QueueSampler sampler;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");
    }

    @Test
    void 各类状态被正确归类() {
        // 已到期的 PENDING：10 分钟前就该投了 —— 这是 oldestOverdue 的来源
        insert("PENDING", "DATE_SUB(NOW(3), INTERVAL 10 MINUTE)");
        // 还没到点的 PENDING（正常刚提交的样子）
        insert("PENDING", "DATE_ADD(NOW(3), INTERVAL 5 SECOND)");
        // 已在退避中的 RETRY_WAIT，3 分钟前到期（说明 worker 没跟上）
        insert("RETRY_WAIT", "DATE_SUB(NOW(3), INTERVAL 3 MINUTE)");
        // 在途
        insert("PROCESSING", "NOW(3)");
        insert("DEAD", "DATE_SUB(NOW(3), INTERVAL 1 HOUR)");
        insert("DEAD", "DATE_SUB(NOW(3), INTERVAL 2 HOUR)");
        insert("SUCCEEDED", "DATE_SUB(NOW(3), INTERVAL 1 HOUR)");

        sampler.sample();
        QueueStats stats = sampler.current();

        assertThat(stats).isNotNull();
        // backlog = PENDING + RETRY_WAIT，在途不算（它有 worker 在跑，不是"等着")
        assertThat(stats.backlog()).isEqualTo(3);
        assertThat(stats.processing()).isEqualTo(1);
        assertThat(stats.dead()).isEqualTo(2);
        // 最老到期的是那条 10 分钟前的 PENDING；未来那条不参与
        assertThat(stats.oldestOverdueSeconds()).isBetween(595L, 620L);
    }

    /** 没有任何到期的任务时年龄必须是 0，而不是"负的几秒"或 NULL——背压拿它直接比阈值。 */
    @Test
    void 没有到期任务时逾期为零() {
        insert("PENDING", "DATE_ADD(NOW(3), INTERVAL 1 MINUTE)");
        insert("SUCCEEDED", "NOW(3)");

        sampler.sample();

        assertThat(sampler.current().oldestOverdueSeconds()).isZero();
        assertThat(sampler.current().backlog()).isEqualTo(1);
    }

    @Test
    void 空库时各项为零而不是null() {
        sampler.sample();

        QueueStats stats = sampler.current();
        assertThat(stats.backlog()).isZero();
        assertThat(stats.dead()).isZero();
        assertThat(stats.oldestOverdueSeconds()).isZero();
        assertThat(stats.sampledAt()).isNotNull();
    }

    private void insert(String status, String nextAttemptExpr) {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, status, attempt_count, max_attempts,
                     next_attempt_at)
                VALUES ('shop', ?, 'https://crm.example.com/cb', 'POST', ?, 1, 10, %s)
                """.formatted(nextAttemptExpr), "qs:" + status + ":" + System.nanoTime(), status);
    }
}
