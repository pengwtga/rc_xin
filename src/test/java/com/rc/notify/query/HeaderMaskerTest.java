package com.rc.notify.query;

import com.rc.notify.query.dto.MaskedHeader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Header 脱敏规则（T3.1）：白名单回显、其余一律占位符。
 *
 * <p>这类规则的单测价值在于"钉死默认值"：默认全遮、白名单才放。
 * 将来若有人为了方便排障往白名单里加 {@code authorization}，这里的用例会立刻变红。
 */
class HeaderMaskerTest {

    private final HeaderMasker masker = new HeaderMasker();

    @Test
    void 白名单头回显原值_其余脱敏() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("Authorization", "Bearer super-secret");
        raw.put("Content-Type", "application/json");
        raw.put("X-Supplier-Secret", "s3cr3t");

        List<MaskedHeader> masked = masker.mask(raw);

        assertThat(valueOf(masked, "Authorization")).isEqualTo(HeaderMasker.MASK);
        assertThat(valueOf(masked, "Content-Type")).isEqualTo("application/json");
        assertThat(valueOf(masked, "X-Supplier-Secret")).isEqualTo(HeaderMasker.MASK);
    }

    @Test
    void 凭证类头一律脱敏_大小写不敏感() {
        // HTTP 头名不敏感：AUTHORIZATION 与 authorization 是同一个头，不能被大小写绕过
        for (String name : List.of("AUTHORIZATION", "authorization", "AuthoriZation", "COOKIE", "X-Api-Key")) {
            assertThat(valueOf(masker.mask(Map.of(name, "v")), name))
                    .as("头 %s 必须脱敏", name)
                    .isEqualTo(HeaderMasker.MASK);
        }
    }

    @Test
    void 输出按名字排序_同一份数据每次一致() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("X-Request-Id", "req-1");
        raw.put("Accept", "application/json");
        raw.put("Authorization", "Bearer t");

        List<MaskedHeader> masked = masker.mask(raw);

        assertThat(masked).extracting(MaskedHeader::name)
                .containsExactly("Accept", "Authorization", "X-Request-Id");
    }

    @Test
    void 无头时返回空列表() {
        assertThat(masker.mask(null)).isEmpty();
        assertThat(masker.mask(Map.of())).isEmpty();
    }

    @Test
    void 白名单头过长时截断() {
        String longValue = "a".repeat(HeaderMasker.MAX_PLAIN_LEN + 50);

        String masked = valueOf(masker.mask(Map.of("User-Agent", longValue)), "User-Agent");

        assertThat(masked).hasSize(HeaderMasker.MAX_PLAIN_LEN + 1).endsWith("…");
    }

    private static String valueOf(List<MaskedHeader> headers, String name) {
        return headers.stream()
                .filter(h -> h.name().equalsIgnoreCase(name))
                .findFirst()
                .map(MaskedHeader::value)
                .orElseThrow(() -> new AssertionError("响应里没有头 " + name));
    }
}
