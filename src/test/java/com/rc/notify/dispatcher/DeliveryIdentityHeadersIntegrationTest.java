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
 * 下游去重契约的集成验证（设计文档 §2.3）：每次投递强制携带
 * {@code X-Notification-Id}（我方单号）与 {@code X-Idempotency-Key}（调用方幂等键）。
 *
 * <p>这条契约的来历值得记住：文档曾写"投递请求强制携带"，实现里却一个头都没注入——
 * 校验设计文档时发现的第 11 处偏差，也是其中唯一需要补代码的。at-least-once 语义下
 * 双投必然发生，下游若拿不到稳定标识就等于没有去重手段，所以注入点收敛在
 * {@link HttpDeliverer}（线上发什么头只看一个类），数据来源是认领回读的
 * {@code idempotency_key}（调用方提交时已入库，不依赖调用方在 headers 里自己带）。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false",
        "notify.ssrf.allowed-hosts=127.0.0.1"
})
class DeliveryIdentityHeadersIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;

    private com.sun.net.httpserver.HttpServer mock;
    private int mockPort;
    /** 下游每次收到的请求头（一个条目 = 一次投递）。保留 Headers 原对象：它对 key 不区分大小写，拷成普通 Map 会丢这个性质。 */
    private final List<com.sun.net.httpserver.Headers> receivedHeaders = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");

        mock = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/", exchange -> {
            try (var in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            receivedHeaders.add(exchange.getRequestHeaders());
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
    void 真实投递携带单号与幂等键_调用方自定义头不受影响() {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                     status, attempt_count, max_attempts, next_attempt_at)
                VALUES ('shop', 'order_paid:pay_001', ?, 'POST', NULL, NULL,
                        'PENDING', 0, 10, NOW(3))
                """, "http://127.0.0.1:" + mockPort + "/cb");
        long id = jdbc.queryForObject("SELECT id FROM notification ORDER BY id DESC LIMIT 1", Long.class);

        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        await(() -> "SUCCEEDED".equals(status(id)), "投递成功");

        assertThat(receivedHeaders).hasSize(1);
        com.sun.net.httpserver.Headers headers = receivedHeaders.get(0);

        // 身份头存在且值正确（HttpServer 的 Headers key 不区分大小写）
        assertThat(first(headers, "X-Notification-Id")).isEqualTo(String.valueOf(id));
        assertThat(first(headers, "X-Idempotency-Key")).isEqualTo("order_paid:pay_001");

        // 每个身份头只有一个值——出现两个值对下游等于没有去重键
        assertThat(headers.get("X-Notification-Id")).hasSize(1);
        assertThat(headers.get("X-Idempotency-Key")).hasSize(1);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id);
    }

    private static String first(com.sun.net.httpserver.Headers headers, String name) {
        List<String> v = headers.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
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
