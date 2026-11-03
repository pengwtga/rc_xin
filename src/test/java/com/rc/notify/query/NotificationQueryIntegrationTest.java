package com.rc.notify.query;

import com.jayway.jsonpath.JsonPath;
import com.rc.notify.ingest.ApiKeyAuthFilter;
import com.rc.notify.store.PayloadCodec;
import com.rc.notify.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3.1 查询接口集成验收（真实 MySQL）。
 *
 * <p>覆盖任务拆解里点名要求的四项，外加五个"接口一上线就会遇到"的边界：
 * <ol>
 *   <li>查单返回状态 + 每轮 attempt（状态码 / 耗时 / 错误分类 / 响应片段）；</li>
 *   <li>越权查他人单号返回 404，且与"不存在"的响应完全一致（不泄露存在性）；</li>
 *   <li>Header 脱敏、body 不回显；</li>
 *   <li>列表按状态过滤 + 键集翻页不重不漏；</li>
 *   <li>参数非法：类型层面失败 400 / 内容不合法 422（含 `{id}=abc` 不能是 500）；</li>
 *   <li>密文损坏时降级而不是 500；明细超上限时截断到最近的 200 条。</li>
 * </ol>
 *
 * <p>本地运行：{@code docker compose up -d && MYSQL_IT=true mvn test}。调度器关闭，数据全部由 SQL 造，
 * header/body 一律走真实 {@link PayloadCodec} 编码——手写明文十六进制在 T2.4 换加密后踩过一次坑
 * （库里出现"不支持的密文版本: 123"，123 就是 `{`）。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false"
})
@AutoConfigureMockMvc
class NotificationQueryIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY_SHOP = "k_shop";          // -> caller: shop
    private static final String KEY_CRM = "k_crm";            // -> caller: crm

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PayloadCodec codec;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");
    }

    // ——— 详情 ———

    @Test
    void 查自己的单_返回状态与逐次明细() throws Exception {
        long id = insert("shop", "order_paid:e1", "DEAD", 2, 3, null, null);
        insertAttempt(id, 1, 1200, null, "TIMEOUT", null);                       // 网络层失败：没有状态码
        insertAttempt(id, 2, 800, 502, "HTTP_5XX", "<html>bad gateway</html>");
        // 最近一次结果由投递引擎在落终态时写回（这里按真实路径补齐，不是查询侧算出来的）
        jdbc.update("UPDATE notification SET last_status_code = 502, last_error_class = 'HTTP_5XX' WHERE id = ?", id);

        mockMvc.perform(get("/v1/notifications/{id}", id).header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification_no").value(id))
                .andExpect(jsonPath("$.status").value("DEAD"))
                .andExpect(jsonPath("$.idempotency_key").value("order_paid:e1"))
                .andExpect(jsonPath("$.attempt_count").value(2))
                .andExpect(jsonPath("$.max_attempts").value(3))
                .andExpect(jsonPath("$.last_status_code").value(502))
                .andExpect(jsonPath("$.last_error_class").value("HTTP_5XX"))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                // 明细按 attempt_no 升序：先失败的那次在前面，读起来才是时间线
                .andExpect(jsonPath("$.attempts[0].attempt_no").value(1))
                .andExpect(jsonPath("$.attempts[0].duration_ms").value(1200))
                .andExpect(jsonPath("$.attempts[0].status_code").doesNotExist())   // 网络层失败为 null
                .andExpect(jsonPath("$.attempts[0].error_class").value("TIMEOUT"))
                .andExpect(jsonPath("$.attempts[1].attempt_no").value(2))
                .andExpect(jsonPath("$.attempts[1].status_code").value(502))
                .andExpect(jsonPath("$.attempts[1].response_snippet").value("<html>bad gateway</html>"))
                // 时间必须是带 Z 的 UTC，不能让客户端自己猜时区
                .andExpect(jsonPath("$.created_at").value(org.hamcrest.Matchers.endsWith("Z")));
    }

    @Test
    void 越权查他人单号返回404且与不存在的响应完全一致() throws Exception {
        long shopId = insert("shop", "order_paid:e2", "SUCCEEDED", 1, 3, null, null);

        MvcResult other = mockMvc.perform(get("/v1/notifications/{id}", shopId)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_CRM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn();

        MvcResult missing = mockMvc.perform(get("/v1/notifications/{id}", 999_999_999L)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_CRM))
                .andExpect(status().isNotFound())
                .andReturn();

        // 两个 404 的响应体逐字节相同：否则"别人的单"与"不存在的单"就成了存在性探测器
        assertThat(other.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString());
    }

    @Test
    void 未携带凭证返回401() throws Exception {
        long id = insert("shop", "order_paid:e3", "PENDING", 0, 3, null, null);

        mockMvc.perform(get("/v1/notifications/{id}", id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void header按白名单回显_其余脱敏且明文不出现在响应中() throws Exception {
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer super-secret-token",
                "Content-Type", "application/json",
                "X-Supplier-Secret", "tok-9f8e7d");
        long id = insert("shop", "order_paid:e4", "SUCCEEDED", 1, 3, headers, "{\"phone\":\"13800001111\"}");

        MvcResult result = mockMvc.perform(get("/v1/notifications/{id}", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headers_decodable").value(true))
                .andExpect(jsonPath("$.headers.length()").value(3))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        Map<String, String> shown = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> headersJson = JsonPath.read(json, "$.headers");
        headersJson.forEach(h -> shown.put((String) h.get("name"), (String) h.get("value")));

        assertThat(shown)
                .containsEntry("Authorization", "****")
                .containsEntry("X-Supplier-Secret", "****")
                .containsEntry("Content-Type", "application/json");
        // 真正的验收点：两个凭证的明文一个字节都不该出现在响应里
        assertThat(json).doesNotContain("super-secret-token").doesNotContain("tok-9f8e7d");
    }

    @Test
    void body不回显_只回答有没有_且明文手机号不出现在响应中() throws Exception {
        long id = insert("shop", "order_paid:e5", "SUCCEEDED", 1, 3, null, "{\"phone\":\"13800001111\"}");

        MvcResult result = mockMvc.perform(get("/v1/notifications/{id}", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_body").value(true))
                .andExpect(jsonPath("$.body").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("13800001111");
    }

    @Test
    void 无明细的待投任务返回空数组() throws Exception {
        long id = insert("shop", "order_paid:e6", "PENDING", 0, 3, null, null);

        mockMvc.perform(get("/v1/notifications/{id}", id).header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(0))
                .andExpect(jsonPath("$.has_body").value(false))
                .andExpect(jsonPath("$.next_attempt_at").exists());
    }

    @Test
    void header密文损坏时降级为不可解而不是500() throws Exception {
        long id = insert("shop", "order_paid:e7", "SUCCEEDED", 1, 3, Map.of("Authorization", "Bearer x"), null);
        // 模拟密钥轮换/历史数据损坏：密文结构不合法
        jdbc.update("UPDATE notification SET headers_enc = X'0102' WHERE id = ?", id);

        mockMvc.perform(get("/v1/notifications/{id}", id).header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headers_decodable").value(false))
                .andExpect(jsonPath("$.headers.length()").value(0))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));   // 其余字段照常可用
    }

    @Test
    void 明细超过上限时只返回最近200条_attempt_count可判断被截断() throws Exception {
        long id = insert("shop", "order_paid:e8", "DEAD", 205, 300, null, null);
        insertAttempts(id, 205);

        MvcResult result = mockMvc.perform(get("/v1/notifications/{id}", id)
                        .header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempt_count").value(205))
                .andExpect(jsonPath("$.attempts.length()").value(NotificationQueryService.MAX_ATTEMPT_ROWS))
                .andReturn();

        // 截断的是头部（最早的 5 次），保留最近 200 条，且仍然按升序
        List<Integer> nos = JsonPath.read(result.getResponse().getContentAsString(), "$.attempts[*].attempt_no");
        assertThat(nos).hasSize(NotificationQueryService.MAX_ATTEMPT_ROWS);
        assertThat(nos.get(0)).isEqualTo(6);
        assertThat(nos.get(nos.size() - 1)).isEqualTo(205);
    }

    // ——— 列表 ———

    @Test
    void 列表按单号倒序_键集翻页不重不漏() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(insert("shop", "order_paid:page_" + i, "DEAD", 3, 3, null, null));
        }

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        for (int page = 0; page < 5; page++) {   // 最多翻 5 页，翻不出 5 条就是漏了
            String url = cursor == null
                    ? "/v1/notifications?limit=2"
                    : "/v1/notifications?limit=2&cursor=" + cursor;
            MvcResult r = mockMvc.perform(get(url).header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = r.getResponse().getContentAsString();
            List<Number> pageIds = JsonPath.read(body, "$.items[*].notification_no");
            pageIds.forEach(n -> seen.add(n.longValue()));
            Number next = JsonPath.read(body, "$.next_cursor");
            if (next == null) {
                break;
            }
            cursor = next.longValue();
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(ids);   // 不重不漏
        assertThat(seen).isSortedAccordingTo((a, b) -> Long.compare(b, a));   // 倒序
        assertThat(seen).hasSize(5);
    }

    @Test
    void 列表按状态过滤_大小写不敏感() throws Exception {
        insert("shop", "order_paid:s1", "DEAD", 3, 3, null, null);
        insert("shop", "order_paid:s2", "DEAD", 3, 3, null, null);
        insert("shop", "order_paid:s3", "SUCCEEDED", 1, 3, null, null);

        mockMvc.perform(get("/v1/notifications?status=dead").header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("DEAD"))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());   // 没有下一页必须是 null
    }

    @Test
    void 列表不返回他人数据() throws Exception {
        insert("shop", "order_paid:t1", "DEAD", 3, 3, null, null);
        insert("shop", "order_paid:t2", "DEAD", 3, 3, null, null);
        long crmId = insert("crm", "order_paid:t3", "DEAD", 3, 3, null, null);

        MvcResult r = mockMvc.perform(get("/v1/notifications").header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_CRM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn();

        List<Number> ids = JsonPath.read(r.getResponse().getContentAsString(), "$.items[*].notification_no");
        assertThat(ids).extracting(Number::longValue).containsExactly(crmId);
    }

    // ——— 参数校验：400 是"解析不了"，422 是"解析了但不合法" ———

    @Test
    void 非法状态或越界页大小返回422() throws Exception {
        mockMvc.perform(get("/v1/notifications?status=FOO").header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/v1/notifications?limit=0").header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/v1/notifications?limit=101").header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/v1/notifications?cursor=0").header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 非数字游标与非数字单号返回400而不是500() throws Exception {
        mockMvc.perform(get("/v1/notifications?cursor=abc").header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        // T3.1 之前这条会落到 Exception 兜底变成 500：纯客户端错误被打成服务端故障
        mockMvc.perform(get("/v1/notifications/{id}", "abc").header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        mockMvc.perform(get("/v1/notifications?limit=x").header(ApiKeyAuthFilter.HEADER_API_KEY, KEY_SHOP))
                .andExpect(status().isBadRequest());
    }

    // ——— 造数工具（header/body 走真实 codec）———

    private long insert(String callerId, String idemKey, String status, int attemptCount, int maxAttempts,
                        Map<String, String> headers, String body) {
        byte[] headersEnc = codec.encodeHeaders(headers);
        byte[] bodyEnc = codec.encodeBody(body);
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO notification
                        (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                         status, attempt_count, max_attempts, next_attempt_at)
                    VALUES (?, ?, 'https://crm.example.com/cb', 'POST', ?, ?, ?, ?, ?, NOW(3))
                    """, new String[]{"id"});
            ps.setString(1, callerId);
            ps.setString(2, idemKey);
            ps.setBytes(3, headersEnc);
            ps.setBytes(4, bodyEnc);
            ps.setString(5, status);
            ps.setInt(6, attemptCount);
            ps.setInt(7, maxAttempts);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 notification 未返回自增 id");
        }
        return key.longValue();
    }

    private void insertAttempt(long notificationId, int attemptNo, long durationMs,
                               Integer statusCode, String errorClass, String snippet) {
        jdbc.update("""
                        INSERT INTO delivery_attempt
                            (notification_id, attempt_no, started_at, duration_ms, status_code, error_class, response_snippet)
                        VALUES (?, ?, NOW(3), ?, ?, ?, ?)
                        """,
                notificationId, attemptNo, durationMs, statusCode, errorClass, snippet);
    }

    /** 批量造 N 条明细（截断用例需要 200+ 行）；started_at 用 Java 侧时间戳，避免 SQL 里算 INTERVAL。 */
    private void insertAttempts(long notificationId, int count) {
        List<Object[]> batch = new ArrayList<>(count);
        var base = java.time.Instant.now();
        for (int i = 1; i <= count; i++) {
            batch.add(new Object[]{notificationId, i,
                    java.sql.Timestamp.from(base.minusSeconds(count - i)),
                    100 + i, 502, "HTTP_5XX", "err-" + i});
        }
        jdbc.batchUpdate("""
                        INSERT INTO delivery_attempt
                            (notification_id, attempt_no, started_at, duration_ms, status_code, error_class, response_snippet)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                batch);
    }
}
