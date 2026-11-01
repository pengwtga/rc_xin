package com.rc.notify.dispatcher;

import com.rc.notify.store.ClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1.2 集成验收（真实 MySQL + 本地 HttpServer）。
 *
 * <p>默认跳过（无 DB 环境 mvn test 保持绿）；本地验证：
 * <pre>docker compose up -d &amp;&amp; MYSQL_IT=true mvn test</pre>
 * 调度器已置 false：投递全部由测试手动调用 pollOnce() 驱动（§9.2 用例 7）。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:33061/notify?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        // T2.4：投递期会拦回环/私有地址，测试打的是 127.0.0.1，必须显式放行
        "notify.ssrf.allowed-hosts=127.0.0.1"
})
@EnabledIfEnvironmentVariable(named = "MYSQL_IT", matches = "true")
class DispatcherIntegrationTest {

    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private ClaimRepository claims;
    @Autowired
    private JdbcTemplate jdbc;
    /** 造数据必须走真实 codec（T2.4 起是 AES-GCM）：直接写明文会让投递前解密失败。 */
    @Autowired
    private com.rc.notify.store.PayloadCodec codec;

    private com.sun.net.httpserver.HttpServer mock;
    private int mockPort;
    private final java.util.List<String> mockHits = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");

        mock = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            mockHits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " body=" + new String(body));
            byte[] resp = "{\"received\":true}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        mock.start();
        mockPort = mock.getAddress().getPort();
    }

    private void tearDownMock() {
        mock.stop(0);
    }

    private void insertPending(String key, String url, String method, int maxAttempts) {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                     status, attempt_count, max_attempts, next_attempt_at)
                VALUES ('shop', ?, ?, ?, ?, ?, 'PENDING', 0, ?, NOW(3))
                """, key, url, method,
                codec.encodeHeaders(java.util.Map.of("Authorization", "Bearer tok")),
                codec.encodeBody("{\"x\":1}"), maxAttempts);
    }

    private long soleId() {
        return jdbc.queryForObject("SELECT id FROM notification ORDER BY id DESC LIMIT 1", Long.class);
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

    @Test
    void 端到端_提交认领投递成功() throws Exception {
        insertPending("e2e_ok", "http://127.0.0.1:" + mockPort + "/cb", "POST", 10);
        long id = soleId();

        int n = dispatcher.pollOnce();
        assertThat(n).isEqualTo(1);

        await(() -> "SUCCEEDED".equals(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id=" + id, String.class)), "SUCCEEDED");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, lease_owner, lease_until, last_status_code FROM notification WHERE id=" + id);
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("last_status_code")).isEqualTo(200);
        assertThat(row.get("lease_owner")).isNull();      // 投递完成租约清空
        assertThat(row.get("lease_until")).isNull();

        Map<String, Object> att = jdbc.queryForMap(
                "SELECT attempt_no, status_code, error_class, duration_ms, response_snippet FROM delivery_attempt WHERE notification_id=" + id);
        assertThat(att.get("attempt_no")).isEqualTo(1);
        assertThat(att.get("status_code")).isEqualTo(200);
        assertThat(att.get("error_class")).isNull();
        assertThat((String) att.get("response_snippet")).contains("received");

        assertThat(mockHits).hasSize(1);
        assertThat(mockHits.get(0)).contains("POST /cb", "auth=Bearer tok", "body={\"x\":1}");
        tearDownMock();
    }

    @Test
    void 下游五百进入RETRY_WAIT且退避在未来() throws Exception {
        mock.createContext("/5xx", exchange -> {
            mockHits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        insertPending("e2e_5xx", "http://127.0.0.1:" + mockPort + "/5xx", "POST", 10);
        long id = soleId();

        dispatcher.pollOnce();
        await(() -> "RETRY_WAIT".equals(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id=" + id, String.class)), "RETRY_WAIT");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempt_count, last_status_code, "
                        + "next_attempt_at > NOW(3) AS in_future FROM notification WHERE id=" + id);
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("last_status_code")).isEqualTo(500);
        assertThat(((Number) row.get("in_future")).intValue()).isEqualTo(1); // 最小指数退避：base=10s

        // 第二轮轮询：退避未到，不应被再次认领
        int second = dispatcher.pollOnce();
        assertThat(second).isZero();
        assertThat(mockHits).hasSize(1);
        tearDownMock();
    }

    @Test
    void 预算耗尽转DEAD且不再被认领() throws Exception {
        mock.createContext("/5xx", exchange -> {
            mockHits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        insertPending("e2e_dead", "http://127.0.0.1:" + mockPort + "/5xx", "POST", 1);
        long id = soleId();

        dispatcher.pollOnce();
        await(() -> "DEAD".equals(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id=" + id, String.class)), "DEAD");

        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM notification WHERE id=" + id, Integer.class)).isEqualTo(1);

        // DEAD 不在认领条件内：再轮询也不动它
        dispatcher.pollOnce();
        Thread.sleep(200);
        assertThat(mockHits).hasSize(1);
        assertThat(jdbc.queryForList(
                "SELECT attempt_no FROM delivery_attempt WHERE notification_id=" + id, Integer.class))
                .containsExactly(1);
        tearDownMock();
    }

    @Test
    void 租约过期的PROCESSING行可被重领_崩溃回收即认领条件() {
        // 模拟旧实例崩溃残留：PROCESSING + 租约已过期
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                     status, attempt_count, max_attempts, next_attempt_at, lease_owner, lease_until)
                VALUES ('shop', 'e2e_zombie', ?, 'POST', NULL, NULL,
                        'PROCESSING', 1, 10, NOW(3) - INTERVAL 1 MINUTE, 'dead-instance', NOW(3) - INTERVAL 1 SECOND)
                """, "http://127.0.0.1:" + mockPort + "/cb");
        long id = soleId();

        int n = dispatcher.pollOnce();
        assertThat(n).isEqualTo(1);
        // 核心断言：过期租约被回收，attempt_count 又自增了一次
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM notification WHERE id=" + id, Integer.class)).isEqualTo(2);
        // 投递是异步的，状态可能是"在投"或"已完成"，但不可能还是 PENDING（那说明没被认领）
        assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE id=" + id, String.class))
                .isIn("PROCESSING", "SUCCEEDED");
    }

    @Test
    void 守卫失败不覆盖状态_结果过期被丢弃() {
        insertPending("e2e_guard", "http://stub", "POST", 10);
        long id = soleId();

        var claimed = claims.claim("token-A", 1, 60);
        assertThat(claimed).hasSize(1);

        // 旧 token 的过期结果：必须被守卫拒绝
        assertThat(claims.markSucceeded(id, "token-STALE", 200)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE id=" + id, String.class))
                .isEqualTo("PROCESSING");

        // 正确 token：成功落库
        assertThat(claims.markSucceeded(id, "token-A", 200)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE id=" + id, String.class))
                .isEqualTo("SUCCEEDED");

        // 终态行不可再被认领
        assertThat(claims.claim("token-B", 10, 60)).isEmpty();
    }

    @Test
    void 明细只追加_守卫失败的attempt也留痕() throws Exception {
        // 复现"投递完成但租约已转移"：明细落了、状态没落 —— 下游可能收到两次的直接证据
        insertPending("e2e_fact", "http://stub", "POST", 10);
        long id = soleId();

        claims.claim("token-A", 1, 60);
        // 抢先模拟租约被他人接管
        jdbc.update("UPDATE notification SET lease_owner='token-B' WHERE id=" + id);

        claims.insertAttempt(id, 1, new Timestamp(System.currentTimeMillis()), 12, 200, null, "ok");
        boolean applied = claims.markSucceeded(id, "token-A", 200);
        assertThat(applied).isFalse();

        // 明细留痕（事实发生过），状态与租约未被覆盖
        assertThat(jdbc.queryForList(
                "SELECT status_code FROM delivery_attempt WHERE notification_id=" + id, Integer.class))
                .containsExactly(200);
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM notification WHERE id=" + id, String.class))
                .isEqualTo("token-B");
    }

    @Test
    void 四百一次即DEAD不花重试预算() throws Exception {
        mock.createContext("/400", exchange -> {
            mockHits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        insertPending("e2e_400", "http://127.0.0.1:" + mockPort + "/400", "POST", 10);
        long id = soleId();

        dispatcher.pollOnce();
        await(() -> "DEAD".equals(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id=" + id, String.class)), "DEAD");

        // 一次即死：预算只花在可能自愈的失败上（§4.4）
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM notification WHERE id=" + id, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT last_status_code FROM notification WHERE id=" + id, Integer.class)).isEqualTo(400);
        // T2.3：4xx 也必须落下失败类别（此前恒为 NULL，导致按 error_class 的告警看不见 4xx）
        assertThat(jdbc.queryForObject(
                "SELECT last_error_class FROM notification WHERE id=" + id, String.class)).isEqualTo("HTTP_4XX");

        // 再轮询不动 DEAD 行
        dispatcher.pollOnce();
        Thread.sleep(200);
        assertThat(mockHits).hasSize(1);
        tearDownMock();
    }

    @Test
    void 四百二十九带RetryAfter按承诺退避不抖动() throws Exception {
        mock.createContext("/429r", exchange -> {
            mockHits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().add("Retry-After", "120");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        insertPending("e2e_429r", "http://127.0.0.1:" + mockPort + "/429r", "POST", 10);
        long id = soleId();

        dispatcher.pollOnce();
        await(() -> "RETRY_WAIT".equals(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id=" + id, String.class)), "RETRY_WAIT");

        // 对方承诺 120s：恰好在承诺值附近（±2s 容忍落库耗时），不做全抖动
        Integer diff = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, NOW(3), next_attempt_at) FROM notification WHERE id=" + id,
                Integer.class);
        assertThat(diff).isBetween(118, 122);
        tearDownMock();
    }

    @Test
    void 四百二十九无RetryAfter走全抖动公式() throws Exception {
        mock.createContext("/429", exchange -> {
            mockHits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        insertPending("e2e_429", "http://127.0.0.1:" + mockPort + "/429", "POST", 10);
        long id = soleId();

        dispatcher.pollOnce();
        await(() -> "RETRY_WAIT".equals(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id=" + id, String.class)), "RETRY_WAIT");

        // base=10s、jitter=0.5 → [5s, 15s]，±1s 容忍落库耗时
        Integer diff = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, NOW(3), next_attempt_at) FROM notification WHERE id=" + id,
                Integer.class);
        assertThat(diff).isBetween(4, 16);
        tearDownMock();
    }
}
