package com.rc.notify.archive;

import com.rc.notify.store.PayloadCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3.3 归档集成验收（真实 MySQL）。
 *
 * <p>四条数据覆盖两档保留期的两侧边界，外加一条"非终态不动"：
 * <pre>
 *   SUCCEEDED 25h 前  → 清（成功档 >24h）
 *   SUCCEEDED 23h 前  → 留（还没到 24h）
 *   DEAD       8d  前 → 清（失败档 >7d）
 *   DEAD       6d  前 → 留（要撑到人工重放）
 *   PENDING    8d  前 → 留（不是终态，清了就没法投了）
 * </pre>
 *
 * <p>归档关掉调度器（{@code notify.archive.enabled=false} 连 bean 都不注册），由测试手动调
 * {@code runOnce()}——否则断言依赖"任务有没有在测试期间跑过"。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false",
        "notify.archive.enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:33061/notify?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
})
@EnabledIfEnvironmentVariable(named = "MYSQL_IT", matches = "true")
class ArchiveIntegrationTest {

    @Autowired
    private ArchiveService service;
    @Autowired
    private JdbcTemplate jdbc;
    /** 造数据必须走真实 codec：密文列写入明文也能存，但那就不是"清理真实密文"的验证了。 */
    @Autowired
    private PayloadCodec codec;

    private long succeededOld;
    private long succeededFresh;
    private long deadOld;
    private long deadFresh;
    private long pendingOld;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");

        succeededOld = insert("SUCCEEDED", "DATE_SUB(NOW(3), INTERVAL 25 HOUR)");
        succeededFresh = insert("SUCCEEDED", "DATE_SUB(NOW(3), INTERVAL 23 HOUR)");
        deadOld = insert("DEAD", "DATE_SUB(NOW(3), INTERVAL 8 DAY)");
        deadFresh = insert("DEAD", "DATE_SUB(NOW(3), INTERVAL 6 DAY)");
        pendingOld = insert("PENDING", "DATE_SUB(NOW(3), INTERVAL 8 DAY)");

        insertAttempt(succeededOld, "echo 13800001111");
        insertAttempt(deadOld, "token abc-123");
    }

    @Test
    void 过了保留期的密文被清掉_没过的与非终态原样保留() {
        ArchiveService.Result result = service.runOnce();

        assertThat(result.successRows()).isEqualTo(1);
        assertThat(result.failedRows()).isEqualTo(1);

        assertThat(payloadCleared(succeededOld)).as("成功档 25h：应清").isTrue();
        assertThat(payloadCleared(succeededFresh)).as("成功档 23h：未到保留期，应留").isFalse();
        assertThat(payloadCleared(deadOld)).as("失败档 8d：应清").isTrue();
        assertThat(payloadCleared(deadFresh)).as("失败档 6d：要撑到人工重放，应留").isFalse();
        assertThat(payloadCleared(pendingOld)).as("PENDING 不是终态，清了就投不出去").isFalse();
    }

    /** 只清密文，不删行：行与明细是"这条为什么没到"的唯一证据（T3.1/T3.2 都依赖它）。 */
    @Test
    void 只清密文不删行_明细行也保留() {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        int attemptsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Integer.class);

        service.runOnce();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_attempt", Integer.class))
                .isEqualTo(attemptsBefore);
        // 状态、错误分类、尝试次数都还在，只是请求体没了
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, max_attempts FROM notification WHERE id = ?", deadOld);
        assertThat(row.get("status")).isEqualTo("DEAD");
    }

    /**
     * 响应片段同样是 PII：有的供应商把手机号甚至 token 回显在错误信息里。
     * 只清请求体不清响应体，等于留了半扇门。
     */
    @Test
    void 明细里的响应片段一并清掉() {
        service.runOnce();

        assertThat(jdbc.queryForObject(
                "SELECT response_snippet FROM delivery_attempt WHERE notification_id = ?",
                String.class, deadOld)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT response_snippet FROM delivery_attempt WHERE notification_id = ?",
                String.class, succeededOld)).isNull();
    }

    /**
     * {@code updated_at} 不能被归档刷新。
     *
     * <p>这一列是 {@code ON UPDATE CURRENT_TIMESTAMP}，语义是"最后一次<b>状态</b>变更"。
     * 归档不是状态变更；被刷新之后 §4.11 里"这行多久没动过"的判断就失真了——
     * 一批每天被清一次的老单，看起来永远像刚处理过。
     */
    @Test
    void 归档不刷新updated_at() {
        service.runOnce();

        Integer stillOld = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE id = ? AND updated_at < DATE_SUB(NOW(3), INTERVAL 24 HOUR)",
                Integer.class, succeededOld);
        assertThat(stillOld).as("归档后 updated_at 应仍是 25 小时前").isEqualTo(1);
    }

    @Test
    void 重复运行幂等_第二轮无行可清() {
        assertThat(service.runOnce().successRows()).isEqualTo(1);

        ArchiveService.Result second = service.runOnce();
        assertThat(second.successRows()).isZero();
        assertThat(second.failedRows()).isZero();
    }

    // ——— 造数据 ———

    private long insert(String status, String updatedAtExpr) {
        byte[] body = codec.encodeBody("{\"phone\":\"13800001111\"}");
        byte[] headers = codec.encodeHeaders(Map.of("Authorization", "Bearer supplier-token"));
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                     status, attempt_count, max_attempts, next_attempt_at, updated_at)
                VALUES ('shop', ?, 'https://crm.example.com/cb', 'POST', ?, ?, ?, 1, 10, NOW(3), %s)
                """.formatted(updatedAtExpr), "arch:" + status + ":" + System.nanoTime(), headers, body, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);
    }

    private void insertAttempt(long notificationId, String snippet) {
        jdbc.update("""
                INSERT INTO delivery_attempt
                    (notification_id, attempt_no, started_at, duration_ms, status_code, error_class, response_snippet)
                VALUES (?, 1, NOW(3), 120, 200, NULL, ?)
                """, notificationId, snippet);
    }

    /** 两个密文列都为 NULL 才算清干净（只清一半等于没清）。 */
    private boolean payloadCleared(long id) {
        Integer cleared = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE id = ? AND body_enc IS NULL AND headers_enc IS NULL",
                Integer.class, id);
        return cleared == 1;
    }
}
