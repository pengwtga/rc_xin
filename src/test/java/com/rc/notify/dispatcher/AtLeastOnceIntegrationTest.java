package com.rc.notify.dispatcher;

import com.rc.notify.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §9.2 用例 3「双投窗口」的集成验证：<b>请求已发出、结果未落库</b>时进程死掉。
 *
 * <p>这条测试验证的是<b>契约</b>，不是缺陷修复——本系统的投递语义是 at-least-once，
 * 下游收到两次是这套设计的必然结果，也是它能做到"不丢"的原因。真正要钉死的是另外两件事：
 * <ol>
 *   <li>重复投递<b>只有</b>发生在崩溃窗口里，不会因为普通重试而凭空多投；</li>
 *   <li>崩溃之后系统仍然收敛（第二次投递成功即落终态），不会无限重投。</li>
 * </ol>
 *
 * <p>模拟手法：真实投递成功一次，然后把库回滚成"HTTP 已发出但 markSucceeded 没写进库"
 * 的样子——状态退回 PROCESSING、租约拨到过去、明细行删掉、attempt_count 保留 1
 * （认领时自增的那次已经提交）。这与 kill -9 在两个语句之间落下磁盘的形态一致。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false",
        "notify.ssrf.allowed-hosts=127.0.0.1"
})
class AtLeastOnceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;

    private com.sun.net.httpserver.HttpServer mock;
    private int mockPort;
    /** 下游实际收到的报文，按到达顺序；用来证明"同一份内容被投了两次"。 */
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");

        mock = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes()));
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

    @Test
    void 请求已发出但结果未落库时崩溃_下游收到两次而系统只记一次尝试() {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, status,
                     attempt_count, max_attempts, next_attempt_at)
                VALUES ('shop', 'alo:1', ?, 'POST', 'PENDING', 0, 10, NOW(3))
                """, "http://127.0.0.1:" + mockPort + "/cb");
        long id = jdbc.queryForObject("SELECT id FROM notification ORDER BY id DESC LIMIT 1", Long.class);

        // 第一次投递：真实发出并成功落库
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        await(() -> "SUCCEEDED".equals(status(id)), "第一次投递成功");
        assertThat(receivedBodies).hasSize(1);

        // 回滚成"结果没落库"的形态：状态回到 PROCESSING、租约已过期、明细不存在
        jdbc.update("""
                UPDATE notification
                   SET status = 'PROCESSING', lease_owner = 'dead-instance/round1',
                       lease_until = NOW(3) - INTERVAL 1 SECOND,
                       next_attempt_at = NOW(3), last_status_code = NULL, last_error_class = NULL,
                       max_attempts = 10
                 WHERE id = ?
                """, id);
        jdbc.update("DELETE FROM delivery_attempt WHERE notification_id = ?", id);

        // 第二个实例接手：同一份报文又发了一次
        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        await(() -> "SUCCEEDED".equals(status(id)), "接手者投递成功");

        assertThat(receivedBodies)
                .as("at-least-once：下游必然收到两次完全相同的请求")
                .hasSize(2);
        assertThat(receivedBodies.get(0)).isEqualTo(receivedBodies.get(1));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, lease_owner, lease_until FROM notification WHERE id = ?", id);
        assertThat(row.get("status")).as("崩溃后仍能收敛到终态，不会无限重投").isEqualTo("SUCCEEDED");
        assertThat(row.get("attempt_count")).isEqualTo(2);
        assertThat(row.get("lease_owner")).isNull();

        // 库里只有接手者写的 attempt_no=2：崩溃那次"没有记录"正是结果没落库的物证。
        // 这也是查单接口只能还原"落过库的事实"的原因（T3.1 文档 §6.1）。
        assertThat(jdbc.queryForList(
                "SELECT attempt_no FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt_no",
                Integer.class, id)).containsExactly(2);

        // 收敛之后不再有第三次
        assertThat(dispatcher.pollOnce()).isZero();
        assertThat(receivedBodies).hasSize(2);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id);
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
