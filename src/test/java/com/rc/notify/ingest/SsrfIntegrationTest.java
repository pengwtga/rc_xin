package com.rc.notify.ingest;

import com.rc.notify.dispatcher.Dispatcher;
import com.rc.notify.store.PayloadCodec;
import com.rc.notify.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T2.4 的出站安全与凭证加密验收（真实 MySQL）。
 *
 * <p>这个类<b>不加白名单</b>（与其他投递类集成测试相反）：本类验证的正是"没有白名单时回环/私有地址
 * 一律拒绝"，加了白名单就测不出东西了。
 *
 * <p>覆盖任务拆解 T2.4 的三条验收：提交 {@code http://169.254.169.254/} 返回 422、
 * {@code http://127.0.0.1} 未加白名单被拒、DB 中 {@code headers_enc} 为密文且日志无明文凭证
 * （日志由"不打印 Header"的代码审查保证，这里验证密文侧）。
 */
@SpringBootTest(properties = {
        "notify.dispatcher.scheduler-enabled=false"
})
@AutoConfigureMockMvc
class SsrfIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private PayloadCodec codec;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");
    }

    // ——— 受理期：立即拒绝 ———

    @Test
    void 提交云厂商metadata地址返回422() throws Exception {
        submit("{\"idempotency_key\":\"ssrf_meta\",\"target_url\":\"http://169.254.169.254/latest/meta-data/\","
                + "\"http_method\":\"GET\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(countNotifications()).isZero();
    }

    @Test
    void 提交阿里云metadata地址也返回422_CGNAT段容易被漏() throws Exception {
        // 100.100.100.200 在 100.64/10，JDK 的 isSiteLocalAddress 不认为它是内网
        submit("{\"idempotency_key\":\"ssrf_ali\",\"target_url\":\"http://100.100.100.200/latest/meta-data/\","
                + "\"http_method\":\"GET\"}")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 提交回环地址返回422() throws Exception {
        submit("{\"idempotency_key\":\"ssrf_loop\",\"target_url\":\"http://127.0.0.1:6379/\","
                + "\"http_method\":\"GET\"}")
                .andExpect(status().isUnprocessableEntity());

        assertThat(countNotifications()).isZero();
    }

    @Test
    void 非白名单目标的明文http被拒() throws Exception {
        submit("{\"idempotency_key\":\"ssrf_http\",\"target_url\":\"http://crm.example.com/cb\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("https")));
    }

    // ——— 存储侧：凭证必须是密文 ———

    @Test
    void 凭证在库里是密文且能正确解回() throws Exception {
        submit("""
                {"idempotency_key":"crypto_1","target_url":"https://crm.example.com/cb",
                 "headers":{"Authorization":"Bearer sk_live_SECRET","X-Sign":"abc"},
                 "body":"{\\"phone\\":\\"13800138000\\"}"}
                """).andExpect(status().isAccepted());

        byte[] headersEnc = jdbc.queryForObject(
                "SELECT headers_enc FROM notification WHERE idempotency_key='crypto_1'", byte[].class);
        byte[] bodyEnc = jdbc.queryForObject(
                "SELECT body_enc FROM notification WHERE idempotency_key='crypto_1'", byte[].class);

        // 1. 原始字节里搜不到明文凭证与手机号
        assertThat(new String(headersEnc, StandardCharsets.ISO_8859_1)).doesNotContain("sk_live_SECRET");
        assertThat(new String(bodyEnc, StandardCharsets.ISO_8859_1)).doesNotContain("13800138000");

        // 2. 但用同一把密钥能完整解回来——证明它是可解密的密文，不是被截断/损坏的字节
        assertThat(codec.decodeHeaders(headersEnc))
                .containsEntry("Authorization", "Bearer sk_live_SECRET")
                .containsEntry("X-Sign", "abc");
        assertThat(codec.decodeBody(bodyEnc)).contains("13800138000");
    }

    // ——— 投递期：真正的防线 ———

    @Test
    void 投递期拦下内网目标_不发请求直接死信() {
        // 模拟"受理时还是公网域名、投递时变成内网"（DNS rebinding）以及"绕过接口直接写库"两种情况：
        // 校验必须在投递前重做，否则这两种都拦不住
        jdbc.update("""
                INSERT INTO notification
                    (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
                     status, attempt_count, max_attempts, next_attempt_at)
                VALUES ('shop', 'ssrf_deliver', 'http://127.0.0.1:6399/cb', 'POST', NULL, NULL,
                        'PENDING', 0, 10, NOW(3))
                """);

        assertThat(dispatcher.pollOnce()).isEqualTo(1);
        awaitTerminal();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, attempt_count, last_error_class, last_status_code
                FROM notification WHERE idempotency_key='ssrf_deliver'
                """);
        assertThat(row.get("status")).isEqualTo("DEAD");
        // 策略拒绝是一次即死：重试一万次目标还是被禁止
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("last_error_class")).isEqualTo("SSRF_BLOCKED");
        assertThat(row.get("last_status_code")).isNull();

        // 请求一个字节都没发出去：耗时为 0，且没有第二次尝试
        Integer duration = jdbc.queryForObject("""
                SELECT duration_ms FROM delivery_attempt
                WHERE notification_id=(SELECT id FROM notification WHERE idempotency_key='ssrf_deliver')
                """, Integer.class);
        assertThat(duration).isZero();
        assertThat(dispatcher.pollOnce()).isZero();
    }

    // ——— 辅助 ———

    private org.springframework.test.web.servlet.ResultActions submit(String body) throws Exception {
        return mockMvc.perform(post("/v1/notifications")
                .header("X-Api-Key", "k_shop")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int countNotifications() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        return n == null ? 0 : n;
    }

    private void awaitTerminal() {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM notification WHERE idempotency_key='ssrf_deliver'", String.class);
            if (!"PROCESSING".equals(status) && !"PENDING".equals(status)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("等待投递结束超时");
    }
}
