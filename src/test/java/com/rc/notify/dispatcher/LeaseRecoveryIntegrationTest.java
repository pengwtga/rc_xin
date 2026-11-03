package com.rc.notify.dispatcher;

import com.rc.notify.store.ClaimRepository;
import com.rc.notify.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2.2 租约与崩溃恢复的集成验收（真实 MySQL + 本地 HttpServer）。
 *
 * <p>与 T1.2 的 {@link DispatcherIntegrationTest} 的分工：那边验证"投递链路本身"，
 * 这边只验证"进程死掉之后发生了什么"，四个场景分别对应任务拆解 T2.2 的验收项：
 * <ol>
 *   <li>租约未到期时，第二个实例认领不到（不重投的第一道防线）；</li>
 *   <li>认领后崩溃（不落库）→ 租约过期 → 被接手并完成，attempt_count 递增；</li>
 *   <li>旧实例复活后的过期结果，既不能改成功也不能改失败；</li>
 *   <li>崩溃确实消耗一次尝试预算，但不会造成无限重投。</li>
 * </ol>
 *
 * <p>本地运行：{@code docker compose up -d && MYSQL_IT=true mvn test}；调度器全程关闭，
 * 投递由测试手动调用 {@code pollOnce()} 驱动（§9.2 用例 7）。
 *
 * <p>不真等 60s：用 SQL 把 lease_until 拨到过去，等价于"60 秒已经过去"——
 * 租约到期与否只由 DB 时钟比较决定，与进程经历过多少真实时间无关。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false",
        // T2.4：投递期会拦回环/私有地址，测试打的是 127.0.0.1，必须显式放行
        "notify.ssrf.allowed-hosts=127.0.0.1"
})
class LeaseRecoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private ClaimRepository claims;
    @Autowired
    private JdbcTemplate jdbc;

    private com.sun.net.httpserver.HttpServer mock;
    private int mockPort;
    private final List<String> mockHits = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");

        mock = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/", exchange -> {
            mockHits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] resp = "{\"received\":true}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        mock.start();
        mockPort = mock.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        mock.stop(0);
    }

    // ——— 1. 租约未到期：谁都抢不走 ———

    @Test
    void 租约未到期的行_另一个实例认领不到() {
        insertPending("lease_hold", "http://127.0.0.1:" + mockPort + "/cb", 10);
        long id = soleId();

        var first = claims.claim("instanceA/round1", 1, 60);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().attemptNo()).isEqualTo(1);

        // 第二个实例（不同 token）此刻来认领：必须是空集，否则就是双投
        assertThat(claims.claim("instanceB/round1", 10, 60)).isEmpty();

        // 行仍属于 A，attempt_count 没有被第二次认领推高
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, lease_owner FROM notification WHERE id=" + id);
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("lease_owner")).isEqualTo("instanceA/round1");
    }

    // ——— 2. 崩溃 → 到期 → 接手 → 完成 ———

    @Test
    void 认领后崩溃_租约过期被另一实例接手并投递成功() {
        insertPending("lease_crash", "http://127.0.0.1:" + mockPort + "/cb", 10);
        long id = soleId();

        // 模拟进程此刻被 kill -9：认领已提交，HTTP 还没发出，什么都没落库
        assertThat(claims.claim("deadA/round1", 1, 60)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id=" + id, String.class)).isEqualTo("PROCESSING");
        assertThat(attemptRows(id)).isEmpty();   // 崩溃的那次没有明细

        // 把时钟快进到"60 秒后"（租约到期与否只由 DB 时钟比较决定）
        expireLease(id);

        int claimed = dispatcher.pollOnce();
        assertThat(claimed).isEqualTo(1);

        await(() -> "SUCCEEDED".equals(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id=" + id, String.class)), "SUCCEEDED");

        // attempt_count 由 1 → 2：崩溃那次也计数（语义见文档 §2.4）
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempt_count, lease_owner, lease_until, last_status_code FROM notification WHERE id=" + id);
        assertThat(row.get("attempt_count")).isEqualTo(2);
        assertThat(row.get("lease_owner")).isNull();      // 落终态后清租约
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("last_status_code")).isEqualTo(200);

        // 下游只收到 1 次（崩溃时请求确实没发出）；明细只有接手者写的 attempt_no=2
        assertThat(mockHits).hasSize(1);
        assertThat(attemptRows(id)).containsExactly(2);
    }

    // ——— 3. 旧实例复活：过期结果不许覆盖新状态 ———

    @Test
    void 旧实例复活_过期结果不能覆盖已成功的状态() {
        insertPending("lease_zombie", "http://127.0.0.1:" + mockPort + "/cb", 10);
        long id = soleId();

        claims.claim("deadA/round1", 1, 60);
        expireLease(id);

        // B 接手并成功
        assertThat(claims.claim("instanceB/round1", 1, 60)).hasSize(1);
        assertThat(claims.markSucceeded(id, "instanceB/round1", 200)).isTrue();

        // A 在 GC 停顿后"复活"，带着同一个 id 的旧结果回来：
        // 无论它说自己成功还是失败，都必须被守卫拒绝——这是"不许把已成功的单子改回待投递"
        assertThat(claims.markSucceeded(id, "deadA/round1", 200)).isFalse();
        assertThat(claims.markFailed(id, "deadA/round1", 10, 500, "HTTP_5XX")).isFalse();
        assertThat(claims.markDead(id, "deadA/round1", 500, "HTTP_5XX")).isFalse();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, lease_owner, next_attempt_at FROM notification WHERE id=" + id);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("lease_owner")).isNull();

        // 终态行不会再被任何人认领
        assertThat(claims.claim("instanceC/round1", 10, 60)).isEmpty();
    }

    // ——— 4. 崩溃消耗一次预算，但不会无限重投 ———

    @Test
    void 崩溃消耗一次尝试预算_预算耗尽即DEAD不会无限重投() {
        // max_attempts=2：第 1 次被崩溃吃掉，第 2 次由接手者真实投递（500）
        insertPending("lease_budget", "http://127.0.0.1:" + mockPort + "/500", 2);
        long id = soleId();
        mock.createContext("/500", exchange -> {
            mockHits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        claims.claim("deadA/round1", 1, 60);      // 尝试 1：崩溃
        expireLease(id);

        dispatcher.pollOnce();                     // 尝试 2：接手，投递得 500
        await(() -> "DEAD".equals(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id=" + id, String.class)), "DEAD");

        // 预算是 2，第 2 次失败后不再重试——崩溃不会让任务在"崩溃-接手"之间无限循环
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM notification WHERE id=" + id, Integer.class)).isEqualTo(2);
        assertThat(mockHits).hasSize(1);

        // DEAD 之后再多轮也不动它
        assertThat(dispatcher.pollOnce()).isZero();
        assertThat(mockHits).hasSize(1);
    }

    // ——— 辅助 ———

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

    /** 把租约拨到过去：等价于"60 秒已经过去"，不必真等。 */
    private void expireLease(long id) {
        jdbc.update("UPDATE notification SET lease_until = NOW(3) - INTERVAL 1 SECOND WHERE id = ?", id);
    }

    private List<Integer> attemptRows(long id) {
        return jdbc.queryForList(
                "SELECT attempt_no FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt_no",
                Integer.class, id);
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
