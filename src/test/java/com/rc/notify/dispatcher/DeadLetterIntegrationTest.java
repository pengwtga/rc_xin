package com.rc.notify.dispatcher;

import com.rc.notify.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2.3 死信终态与尝试预算的集成验收（真实 MySQL + 本地 HttpServer）。
 *
 * <p>覆盖任务拆解 T2.3 的验收项，外加两条最容易写反的顺序约束：
 * <ol>
 *   <li>{@code max_attempts=3}：第 3 次失败后 DEAD，且此后再也认领不到（认领 WHERE 条件查不到它）；</li>
 *   <li>PERMANENT（401）：一次即 DEAD，不消耗预算；</li>
 *   <li>网络层失败：{@code last_error_class} 落 CONN_REFUSED，状态仍是 RETRY_WAIT；</li>
 *   <li>死信出口：reason 能区分"一次即死"与"预算耗尽"，出口抛异常不影响 DEAD 已落库这一事实。</li>
 * </ol>
 *
 * <p>本地运行：{@code docker compose up -d && MYSQL_IT=true mvn test}。调度器关闭，投递由测试手动
 * {@code pollOnce()} 驱动；退避等待靠 SQL 把 {@code next_attempt_at} 拨到过去快进，不真等 10s/20s。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false",
        // T2.4：投递期会拦回环/私有地址，测试打的是 127.0.0.1，必须显式放行
        "notify.ssrf.allowed-hosts=127.0.0.1"
})
@Import(DeadLetterIntegrationTest.SinkConfig.class)
class DeadLetterIntegrationTest extends AbstractIntegrationTest {

    /** 记录到的死信事件（按发生顺序）。 */
    static final List<DeadLetter> RECORDED = new CopyOnWriteArrayList<>();
    /** 抛异常的死信出口被调用次数：验证"出口挂了不影响状态"。 */
    static final AtomicInteger THROWING_CALLS = new AtomicInteger();

    @TestConfiguration
    static class SinkConfig {
        @Bean
        DeadLetterSink recordingSink() {
            return RECORDED::add;
        }

        @Bean
        DeadLetterSink throwingSink() {
            return letter -> {
                THROWING_CALLS.incrementAndGet();
                throw new IllegalStateException("模拟告警出口不可用");
            };
        }
    }

    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;

    private com.sun.net.httpserver.HttpServer mock;
    private int mockPort;
    private final AtomicInteger mockHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");
        RECORDED.clear();
        THROWING_CALLS.set(0);
        mockHits.set(0);

        mock = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/", exchange -> {
            mockHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        mock.start();
        mockPort = mock.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        mock.stop(0);
    }

    // ——— 1. 预算耗尽 ———

    @Test
    void 预算耗尽进入DEAD_且不再被认领() {
        insertPending("dead_budget", "http://127.0.0.1:" + mockPort + "/always502", 3);
        long id = soleId();
        respondWith("/always502", 502);

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(dispatcher.pollOnce()).as("第 %d 次认领", attempt).isEqualTo(1);
            awaitAttempt(id, attempt);
            if (attempt < 3) {
                makeDue(id);
            }
        }

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, attempt_count, max_attempts, last_status_code, last_error_class, lease_owner
                FROM notification WHERE id = ?
                """, id);
        assertThat(row.get("status")).isEqualTo("DEAD");
        assertThat(row.get("attempt_count")).isEqualTo(3);      // 预算是"总尝试次数"，含首次
        assertThat(row.get("last_status_code")).isEqualTo(502);
        assertThat(row.get("last_error_class")).isEqualTo("HTTP_5XX");   // T2.3 之前这里恒为 NULL
        assertThat(row.get("lease_owner")).isNull();            // 终态不持有租约

        // 明细三次都在，且每次都带上了失败类别（之前的缺口：4xx/5xx 的 error_class 为空）
        List<Map<String, Object>> attempts = jdbc.queryForList(
                "SELECT attempt_no, status_code, error_class FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt_no", id);
        assertThat(attempts).hasSize(3);
        assertThat(attempts).allSatisfy(a -> {
            assertThat(a.get("status_code")).isEqualTo(502);
            assertThat(a.get("error_class")).isEqualTo("HTTP_5XX");
        });

        // 验收标准："查 idx_due 无该行"——下面就是认领 SQL 的 WHERE 条件
        assertThat(dueRows()).isZero();

        // 再多轮也不动它：DEAD 不是"被推迟"，是终态
        assertThat(dispatcher.pollOnce()).isZero();
        assertThat(mockHits.get()).isEqualTo(3);

        // 死信事件只宣布一次，原因是被预算花完
        assertThat(RECORDED).hasSize(1);
        assertThat(RECORDED.get(0).reason()).isEqualTo(DeadLetter.Reason.BUDGET_EXHAUSTED);
        assertThat(RECORDED.get(0).attemptCount()).isEqualTo(3);
        assertThat(RECORDED.get(0).lastStatusCode()).isEqualTo(502);
        assertThat(RECORDED.get(0).errorClass()).isEqualTo("HTTP_5XX");
    }

    // ——— 2. PERMANENT 一次即死，不消耗预算 ———

    @Test
    void 永久失败一次即DEAD_预算一次都不花() {
        insertPending("dead_permanent", "http://127.0.0.1:" + mockPort + "/401", 10);
        long id = soleId();
        respondWith("/401", 401);

        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        awaitAttempt(id, 1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, last_status_code, last_error_class FROM notification WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DEAD");
        // 预算 10，只用了 1：401 重试 9 次都是 401，那是拿预算换同一条错误
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("last_error_class")).isEqualTo("HTTP_4XX");

        assertThat(dispatcher.pollOnce()).isZero();
        assertThat(mockHits.get()).isEqualTo(1);

        assertThat(RECORDED).hasSize(1);
        assertThat(RECORDED.get(0).reason()).isEqualTo(DeadLetter.Reason.PERMANENT);
        assertThat(RECORDED.get(0).attemptCount()).isEqualTo(1);
        assertThat(RECORDED.get(0).maxAttempts()).isEqualTo(10);
    }

    // ——— 3. 网络层失败：类别落库，但不进死信（还能自愈） ———

    @Test
    void 网络层失败落CONN_REFUSED_仍是待重试() throws Exception {
        // 拿一个刚被释放的端口，保证无人监听（连接被拒）。不 stop mock：它还供其他用例使用
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        insertPending("dead_net", "http://127.0.0.1:" + closedPort + "/cb", 5);
        long id = soleId();

        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        awaitAttempt(id, 1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, last_error_class, last_status_code FROM notification WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("RETRY_WAIT");
        assertThat(row.get("last_error_class")).isEqualTo("CONN_REFUSED");
        assertThat(row.get("last_status_code")).isNull();      // 没有响应，就没有状态码

        // 没到 DEAD，不该有任何死信事件
        assertThat(RECORDED).isEmpty();
    }

    // ——— 4. 死信出口异常隔离 ———

    @Test
    void 死信出口抛异常不影响DEAD落库() {
        insertPending("dead_sink", "http://127.0.0.1:" + mockPort + "/403", 5);
        long id = soleId();
        respondWith("/403", 403);

        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        awaitAttempt(id, 1);

        // 抛异常的出口在遍历中被调用过，但状态已经是 DEAD——出口失败不许回滚业务事实
        assertThat(THROWING_CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id))
                .isEqualTo("DEAD");
        // 记录型出口仍然收到了事件：一个出口挂了不影响排在它后面的出口
        assertThat(RECORDED).hasSize(1);
    }

    // ——— 辅助 ———

    private void respondWith(String path, int status) {
        mock.createContext(path, exchange -> {
            mockHits.incrementAndGet();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }

    private void insertPending(String key, String url, int maxAttempts) {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                     status, attempt_count, max_attempts, next_attempt_at)
                VALUES ('shop', ?, ?, 'POST', NULL, NULL, 'PENDING', 0, ?, NOW(3))
                """, key, url, maxAttempts);
    }

    private long soleId() {
        return jdbc.queryForObject("SELECT id FROM notification ORDER BY id DESC LIMIT 1", Long.class);
    }

    /** 把下一次到期时刻拨到过去：等价于"退避时间已过"，不必真等 10s。 */
    private void makeDue(long id) {
        jdbc.update("UPDATE notification SET next_attempt_at = NOW(3) - INTERVAL 1 SECOND WHERE id = ?", id);
    }

    /** 认领 SQL 的 WHERE 条件（idx_due 的扫描范围）：DEAD 行不该出现在这里。 */
    private int dueRows() {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification
                WHERE (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= NOW(3))
                   OR (status = 'PROCESSING' AND lease_until < NOW(3))
                """, Integer.class);
        return n == null ? 0 : n;
    }

    /** 等这一轮投递落库完成（attempt_count 到 n 且已离开 PROCESSING）。 */
    private void awaitAttempt(long id, int n) {
        await(() -> {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT attempt_count, status FROM notification WHERE id = ?", id);
            return Integer.valueOf(n).equals(row.get("attempt_count"))
                    && !"PROCESSING".equals(row.get("status"));
        }, "第 " + n + " 次尝试落库");
    }

    private static void await(BooleanSupplier cond, String what) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("等待超时: " + what);
    }
}
