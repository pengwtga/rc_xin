package com.rc.notify.replay;

import com.jayway.jsonpath.JsonPath;
import com.rc.notify.dispatcher.Dispatcher;
import com.rc.notify.ingest.ApiKeyAuthFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3.2 人工重放集成验收（真实 MySQL + 本地 HttpServer）。
 *
 * <p>覆盖任务拆解点名的四条验收，外加审计与轨迹闭环：
 * <ol>
 *   <li>DEAD → 重放 → PENDING，{@code attempt_count} 不归零、预算追加一档；</li>
 *   <li>重放后的单被真实投递成功，attempt_no 连续（不撞 uk_n_attempt）；</li>
 *   <li>对 SUCCEEDED / PENDING 单重放返回 409 且无副作用；重复点击第二次 409；</li>
 *   <li>审计（操作人/原因/预算快照）与状态迁移同事务落库，查单详情能看到。</li>
 * </ol>
 *
 * <p>本地运行：{@code docker compose up -d && MYSQL_IT=true mvn test}。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false",
        "spring.datasource.url=jdbc:mysql://localhost:33061/notify?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "notify.ssrf.allowed-hosts=127.0.0.1"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MYSQL_IT", matches = "true")
class ReplayIntegrationTest {

    private static final String KEY_SHOP = "k_shop";        // -> shop，预算档位 10（全局默认）
    private static final String KEY_INVENTORY = "k_inventory"; // -> inventory，预算档位 6（调用方覆盖）
    private static final String KEY_CRM = "k_crm";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Dispatcher dispatcher;

    private com.sun.net.httpserver.HttpServer mock;
    private int mockPort;
    private final AtomicInteger mockHits = new AtomicInteger();
    /** mock 的健康开关：true=恒 502（制造 DEAD），false=200（验证重放后能成功）。 */
    private final AtomicBoolean unhealthy = new AtomicBoolean(true);

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM replay_log");
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");
        mockHits.set(0);
        unhealthy.set(true);

        mock = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/", exchange -> {
            mockHits.incrementAndGet();
            int status = unhealthy.get() ? 502 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        mock.start();
        mockPort = mock.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        mock.stop(0);
    }

    // ——— 1. 正向路径 ———

    @Test
    void DEAD单重放后回到PENDING_预算追加一档_尝试次数不归零() throws Exception {
        long id = deadViaRealDelivery("replay_basic", 1);

        mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"zhang.san\",\"reason\":\"凭证已更换\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification_no").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempt_count").value(1))      // 不归零
                .andExpect(jsonPath("$.max_attempts").value(11));     // 1 + 档位 10

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, max_attempts, next_attempt_at <= NOW(3) AS due, "
                        + "last_error_class FROM notification WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("due")).intValue()).isEqualTo(1);   // 立即可认领
        // 最近一次失败的事实保留：查单时还能看出"它之前是被什么打死的"
        assertThat(row.get("last_error_class")).isEqualTo("HTTP_5XX");
    }

    @Test
    void 重放后的单真实投递成功_明细序号连续不撞唯一键() throws Exception {
        long id = deadViaRealDelivery("replay_success", 1);
        unhealthy.set(false);   // 供应商"恢复"

        mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"供应商已恢复\"}"))
                .andExpect(status().isOk());

        assertThat(dispatcher.pollOnce()).as("重放后的单应被认领").isEqualTo(1);
        awaitAttempt(id, 2);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, last_status_code FROM notification WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("last_status_code")).isEqualTo(200);

        // attempt_no 1=502、2=200 连续：T2.5 那条"跳过不写明细"的不变式在重放路径同样成立
        List<Map<String, Object>> attempts = jdbc.queryForList(
                "SELECT attempt_no, status_code FROM delivery_attempt WHERE notification_id = ? ORDER BY attempt_no", id);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0)).containsEntry("attempt_no", 1).containsEntry("status_code", 502);
        assertThat(attempts.get(1)).containsEntry("attempt_no", 2).containsEntry("status_code", 200);
    }

    @Test
    void 重放预算档位取调用方当前配置() throws Exception {
        // k_inventory 配了 max-attempts=6：档位按 6 给，而不是全局默认 10。
        // 单必须插在 inventory 名下——单号归属由 caller 决定，借 shop 的单重放是 404
        long id = deadViaRealDelivery("inventory", "replay_tier", 1);

        mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_INVENTORY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"按新档位重试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.max_attempts").value(7));   // 1 + 6
    }

    // ——— 2. 状态守卫 ———

    @Test
    void 对SUCCEEDED单重放返回409且无副作用() throws Exception {
        long id = insertWithStatus("replay_done", "SUCCEEDED", 1, 10);

        mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"误操作重放\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, attempt_count, max_attempts FROM notification WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("max_attempts")).intValue()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM replay_log", Integer.class)).isZero();
    }

    @Test
    void 对待投状态的单重放同样409() throws Exception {
        long id = insertWithStatus("replay_pending", "PENDING", 0, 10);

        mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 重复点击第二次返回409() throws Exception {
        long id = deadViaRealDelivery("replay_twice", 1);
        String body = "{\"reason\":\"第一次点击\"}";

        mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // 状态守卫写在 UPDATE 的 WHERE 里：第二次点击匹配不到 DEAD 行，不需要分布式锁
        mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM replay_log WHERE notification_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    // ——— 3. 鉴权与审计 ———

    @Test
    void 他人单号404且与不存在的响应一致() throws Exception {
        long id = deadViaRealDelivery("replay_other", 1);

        MvcResult other = mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_CRM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult missing = mockMvc.perform(post("/v1/notifications/{id}/replay", 999_999_999L)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_CRM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(other.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString());
        // 单还在，且没被 crm 动过
        assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id))
                .isEqualTo("DEAD");
    }

    @Test
    void 缺reason或超长reason返回422() throws Exception {
        long id = deadViaRealDelivery("replay_reason", 1);
        String url = "/v1/notifications/" + id + "/replay";

        mockMvc.perform(post(url).header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());          // reason 缺失
        mockMvc.perform(post(url).header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity());          // 空白
        mockMvc.perform(post(url).header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(513) + "\"}"))
                .andExpect(status().isUnprocessableEntity());          // 超长

        // 校验失败不该有任何副作用
        assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id))
                .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM replay_log", Integer.class)).isZero();
    }

    @Test
    void 审计落库_操作人缺省为调用方标识() throws Exception {
        long id = deadViaRealDelivery("replay_audit", 1);

        // characterEncoding 必须显式给：MockMvc 的请求体默认按 ISO-8859-1 解码，
        // 中文会变成"ä¾æå¡"式的乱码落库——真实客户端不受影响，这是测试基建的坑
        mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"reason\":\" 供应商已恢复 \"}"))
                .andExpect(status().isOk());

        Map<String, Object> log = jdbc.queryForMap(
                "SELECT operator, reason, attempt_count_before, max_attempts_after FROM replay_log WHERE notification_id = ?", id);
        assertThat(log.get("operator")).isEqualTo("shop");                 // 缺省 = caller
        assertThat(log.get("reason")).isEqualTo("供应商已恢复");            // trim 过
        assertThat(((Number) log.get("attempt_count_before")).intValue()).isEqualTo(1);
        assertThat(((Number) log.get("max_attempts_after")).intValue()).isEqualTo(11);

        // 查单详情的 replays 字段读到同一条审计（T3.1 状态轨迹闭环）
        MvcResult detail = mockMvc.perform(get("/v1/notifications/{id}", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isOk())
                .andReturn();
        // getContentAsString 必须显式给 UTF-8：响应的 Content-Type 是 application/json（不带
        // charset，JSON 规范默认 UTF-8），而 MockMvc 的无参重载按 ISO-8859-1 解码出乱码——
        // 真实客户端按规范走 UTF-8，这是测试断言侧的坑，不是服务端的
        String json = detail.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat((Integer) JsonPath.read(json, "$.replays.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(json, "$.replays[0].operator")).isEqualTo("shop");
        assertThat((String) JsonPath.read(json, "$.replays[0].reason")).isEqualTo("供应商已恢复");
        assertThat((Integer) JsonPath.read(json, "$.replays[0].attempt_count_before")).isEqualTo(1);
    }

    @Test
    void 未携带凭证返回401() throws Exception {
        long id = deadViaRealDelivery("replay_auth", 1);

        mockMvc.perform(post("/v1/notifications/{id}/replay", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ——— 辅助 ———

    /**
     * 用真实投递制造一条 DEAD（比手写 SQL 更有说服力：走的是 T1.2/T2.3 的完整路径）。
     * mock 初始为不健康（502），maxAttempts=1 → 第一次尝试后即 DEAD。
     */
    private long deadViaRealDelivery(String callerId, String key, int maxAttempts) throws Exception {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                     status, attempt_count, max_attempts, next_attempt_at)
                VALUES (?, ?, ?, 'POST', NULL, NULL, 'PENDING', 0, ?, NOW(3))
                """, callerId, key, "http://127.0.0.1:" + mockPort + "/cb", maxAttempts);
        long id = jdbc.queryForObject("SELECT id FROM notification ORDER BY id DESC LIMIT 1", Long.class);

        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        awaitAttempt(id, maxAttempts);
        assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id))
                .as("前置条件：这条单应已进入 DEAD")
                .isEqualTo("DEAD");
        return id;
    }

    private long deadViaRealDelivery(String key, int maxAttempts) throws Exception {
        return deadViaRealDelivery("shop", key, maxAttempts);
    }

    private long insertWithStatus(String key, String status, int attemptCount, int maxAttempts) {
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                     status, attempt_count, max_attempts, next_attempt_at)
                VALUES ('shop', ?, 'https://crm.example.com/cb', 'POST', NULL, NULL, ?, ?, ?, NOW(3))
                """, key, status, attemptCount, maxAttempts);
        return jdbc.queryForObject("SELECT id FROM notification ORDER BY id DESC LIMIT 1", Long.class);
    }

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
