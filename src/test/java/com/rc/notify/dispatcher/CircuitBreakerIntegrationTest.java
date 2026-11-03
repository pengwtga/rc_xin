package com.rc.notify.dispatcher;

import com.rc.notify.store.PayloadCodec;
import com.rc.notify.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2.5 端点隔离与熔断的集成验收（真实 MySQL + 两个本地 HttpServer）。
 *
 * <p>验收标准（任务拆解 T2.5）原话是："mock 一个 host 持续 500 → 该 host 任务进入 RETRY_WAIT
 * 但 attempt_count 不增长；同时另一 host 的投递 p99 不受影响"。这里拆成两条可判定的断言：
 * <ol>
 *   <li>坏端点熔断后，它的 {@code attempt_count} 总和与下游收到的请求数<b>都不再增长</b>；
 *       好端点全部 SUCCEEDED，且没有一条 DEAD（预算没被烧掉）；</li>
 *   <li>被跳过（退还、不写明细）之后，那条任务恢复时仍能正常投递成功——证明退还路径没有
 *       留下 {@code attempt_no} 空洞或 {@code uk_n_attempt} 冲突。</li>
 * </ol>
 *
 * <p>两个 mock 故意用<b>不同端口</b>：端点键是 {@code scheme://host:port}，同端口不同路径属同一端点，
 * 拿同一端口演"两个供应商"是假隔离。
 *
 * <p>本地运行：{@code docker compose up -d && MYSQL_IT=true mvn test}。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false",
        "notify.ssrf.allowed-hosts=127.0.0.1",
        // 小池子 + 小样本：让 4 个失败就能把端点打熔断，测试不必等 60s 或灌 100 条
        "notify.dispatcher.worker-pool-size=4",
        "notify.dispatcher.batch-size-max=4",
        "notify.circuit.min-samples=4",
        "notify.circuit.failure-rate-threshold=0.5",
        "notify.circuit.max-concurrent-per-endpoint=2",
        "notify.circuit.half-open-probes=1",
        "notify.circuit.skip-delay-seconds=1",
        "notify.circuit.permit-delay-seconds=1",
        // 退避缩到秒级，测试靠 SQL 快进但也有真实的短等待
        "notify.backoff.base-ms=1000",
        "notify.backoff.cap-ms=2000"
})
class CircuitBreakerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private EndpointGate gate;
    @Autowired
    private PayloadCodec codec;
    @Autowired
    private JdbcTemplate jdbc;

    private com.sun.net.httpserver.HttpServer badMock;
    private com.sun.net.httpserver.HttpServer goodMock;
    private int badPort;
    private int goodPort;
    private final AtomicInteger badHits = new AtomicInteger();
    /** 让"坏端点"可以被治愈（第二个用例要证明跳过之后还能投成功）。 */
    private final AtomicBoolean badHealthy = new AtomicBoolean(false);

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");
        badHits.set(0);
        badHealthy.set(false);

        badMock = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        badMock.createContext("/cb", exchange -> {
            badHits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (badHealthy.get()) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.close();
        });
        badMock.start();
        badPort = badMock.getAddress().getPort();

        goodMock = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        goodMock.createContext("/cb", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        goodMock.start();
        goodPort = goodMock.getAddress().getPort();

        // 上一个用例可能把某个端点打成 OPEN；端口每个用例都变，但保险起见复位
        gate.reset(endpointOf(badPort));
        gate.reset(endpointOf(goodPort));
    }

    @AfterEach
    void tearDown() {
        badMock.stop(0);
        goodMock.stop(0);
    }

    private static String endpointOf(int port) {
        return "http://127.0.0.1:" + port;
    }

    private String url(int port) {
        return "http://127.0.0.1:" + port + "/cb";
    }

    @Test
    void 坏端点熔断后不再消耗尝试预算_好端点全部成功() {
        for (int i = 0; i < 20; i++) {
            insertPending("bad_" + i, url(badPort), 10);
        }
        for (int i = 0; i < 10; i++) {
            insertPending("good_" + i, url(goodPort), 10);
        }

        // 一直投到熔断器打开（失败样本 >= minSamples 且失败率 > 50%）
        pollUntil("坏端点熔断", () -> gate.isOpen(endpointOf(badPort)));
        // 让在途的投递都落完，再取快照——否则快照会被一个还没结束的请求改写
        sleep(400);

        int attemptsAtOpen = attemptsOf(badPort);
        int hitsAtOpen = badHits.get();
        assertThat(attemptsAtOpen).isGreaterThan(0);

        // 熔断期间继续空转三轮：认领会发生、投递不会发生
        long skippedBefore = dispatcher.skipped();
        for (int i = 0; i < 3; i++) {
            dispatcher.pollOnce();
            fastForwardRetryWait();
            sleep(150);
        }

        // ① 尝试预算没被烧：总和不变（每轮都是"认领 +1 / 退还 -1"）
        assertThat(attemptsOf(badPort)).isEqualTo(attemptsAtOpen);
        // ② 下游真的一次都没再收到请求
        assertThat(badHits.get()).isEqualTo(hitsAtOpen);
        // ③ 跳过发生了（说明确实走了退还路径，而不是没认领到）
        assertThat(dispatcher.skipped()).isGreaterThan(skippedBefore);
        // ④ 没有一条被烧成 DEAD，也没有一条被误判成功：全部停在"等下一次"（没被认领过的仍是 PENDING）
        assertThat(countStatus(badPort, "DEAD")).isZero();
        assertThat(countStatus(badPort, "SUCCEEDED")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE target_url LIKE ? AND status IN ('PENDING','RETRY_WAIT')",
                Integer.class, "%:" + badPort + "/%")).isEqualTo(20);

        // ⑤ 另一个端点不受影响：把坏端点的行按真实语义推到未来（跳过时就是这么做的，只是这里推得更远
        //    以免它们在被反复快进时抢走认领名额），再把好端点的投完
        jdbc.update("UPDATE notification SET next_attempt_at = NOW(3) + INTERVAL 60 SECOND "
                + "WHERE status = 'RETRY_WAIT' AND target_url LIKE ?", "%:" + badPort + "/%");
        pollUntil("好端点全部成功", () -> countStatus(goodPort, "SUCCEEDED") == 10, "%:" + goodPort + "/%");
        assertThat(countStatus(goodPort, "SUCCEEDED")).isEqualTo(10);
        assertThat(countStatus(goodPort, "DEAD")).isZero();
    }

    @Test
    void 被跳过的任务恢复后仍能成功_明细不出现号码空洞() {
        insertPending("recover_1", url(badPort), 10);
        long id = jdbc.queryForObject("SELECT id FROM notification ORDER BY id DESC LIMIT 1", Long.class);

        // 先真的打几次，把端点打到熔断
        pollUntil("坏端点熔断", () -> gate.isOpen(endpointOf(badPort)));
        int attemptsAtOpen = jdbc.queryForObject(
                "SELECT attempt_count FROM notification WHERE id = ?", Integer.class, id);

        // 熔断期间认领一次：这条被跳过（认领 +1 → 退还 -1），attempt_count 必须原地不动
        fastForwardRetryWait();
        dispatcher.pollOnce();
        sleep(200);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM notification WHERE id = ?",
                Integer.class, id)).isEqualTo(attemptsAtOpen);
        assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE id = ?",
                String.class, id)).isEqualTo("RETRY_WAIT");

        // 供应商恢复 + 人工复位熔断（生产里是 HALF_OPEN 探测成功，这里直接复位以缩短用例）
        badHealthy.set(true);
        gate.reset(endpointOf(badPort));
        fastForwardRetryWait();

        pollUntil("恢复后投递成功", () -> "SUCCEEDED".equals(jdbc.queryForObject(
                "SELECT status FROM notification WHERE id = ?", String.class, id)));

        // 明细的 attempt_no 必须是 1..N 连续无重复：退还路径若写过明细，这里会撞 uk_n_attempt
        assertThat(jdbc.queryForList(
                "SELECT attempt_no FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt_no",
                Integer.class, id)).containsExactly(1, 2, 3, 4, 5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempt WHERE notification_id = ? AND status_code = 200",
                Integer.class, id)).isEqualTo(1);
    }

    // —— 辅助 ——

    private void insertPending(String key, String targetUrl, int maxAttempts) {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                     status, attempt_count, max_attempts, next_attempt_at)
                VALUES ('shop', ?, ?, 'POST', ?, ?, 'PENDING', 0, ?, NOW(3))
                """, key, targetUrl,
                codec.encodeHeaders(Map.of("Authorization", "Bearer tok")),
                codec.encodeBody("{\"x\":1}"), maxAttempts);
    }

    private void pollUntil(String what, BooleanSupplier cond) {
        pollUntil(what, cond, null);
    }

    /**
     * 轮询 + 把 RETRY_WAIT 的到期时刻拨到过去（等价于等完退避，但不用真等）。
     *
     * @param urlLike 只快进匹配的行；null = 全部。分开快进是为了模拟"跳过时把行推到 30s 后"
     *                这个真实语义——所有行一起快进的话，熔断中的行会每轮抢走全部认领名额，
     *                把好端点的行饿死，那是测试搭建方式的产物，不是被测行为。
     */
    private void pollUntil(String what, BooleanSupplier cond, String urlLike) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            dispatcher.pollOnce();
            fastForwardRetryWait(urlLike);
            if (cond.getAsBoolean()) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("等待超时: " + what);
    }

    private void fastForwardRetryWait() {
        fastForwardRetryWait(null);
    }

    private void fastForwardRetryWait(String urlLike) {
        if (urlLike == null) {
            jdbc.update("UPDATE notification SET next_attempt_at = NOW(3) - INTERVAL 1 SECOND "
                    + "WHERE status = 'RETRY_WAIT'");
        } else {
            jdbc.update("UPDATE notification SET next_attempt_at = NOW(3) - INTERVAL 1 SECOND "
                    + "WHERE status = 'RETRY_WAIT' AND target_url LIKE ?", urlLike);
        }
    }

    private int attemptsOf(int port) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(attempt_count), 0) FROM notification WHERE target_url LIKE ?",
                Integer.class, "%:" + port + "/%");
    }

    private int countStatus(int port, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE target_url LIKE ? AND status = ?",
                Integer.class, "%:" + port + "/%", status);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
