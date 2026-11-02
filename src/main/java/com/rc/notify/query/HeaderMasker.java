package com.rc.notify.query;

import com.rc.notify.query.dto.MaskedHeader;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 请求头脱敏（T3.1）：<b>白名单回显</b>，不是黑名单遮蔽。
 *
 * <p>为什么不能用敏感名单：黑名单永远漏。今天想到 {@code Authorization}、{@code Cookie}，
 * 明天调用方用 {@code X-Auth-Token}、{@code X-Supplier-Secret}，后天有人把密钥塞进
 * {@code X-Signature-Payload}。漏一次的结果是把下游凭证写进另一个系统的日志与前端页面，
 * 而这类泄漏是不可撤回的。白名单的失效方式是"看不见想看的头"（可接受），
 * 黑名单的失效方式是"泄漏"（不可接受）。
 *
 * <p>只放四个真正与安全无关、排障时又确实要看的值：
 * {@code Content-Type}（对方按什么解析）、{@code Accept}、{@code User-Agent}（有的供应商靠它做风控）、
 * {@code X-Request-Id}（与对方技术支持对齐日志时唯一的锚点）。
 *
 * <p>判断用 {@link Locale#ROOT} 小写化后比对（HTTP 头名大小写不敏感，{@code AUTHORIZATION} 与
 * {@code authorization} 是同一个头，用默认 Locale 会在土耳其语环境把 {@code I} 折成 {@code ı}）。
 */
@Component
public class HeaderMasker {

    /** 脱敏后的占位符。不保留长度、不保留前缀——长度也能泄漏信息（比如"这个 token 是 JWT"）。 */
    public static final String MASK = "****";

    /** 白名单：命中则回显原值（仍做长度截断），未命中一律 {@link #MASK}。 */
    private static final Set<String> PLAIN_NAMES = Set.of(
            "content-type", "accept", "user-agent", "x-request-id");

    /** 白名单头回显时的长度上限（字符）。正常值远小于此，超长说明有人把 payload 塞进了头。 */
    static final int MAX_PLAIN_LEN = 200;

    /**
     * @param raw 已解密的请求头（可为 null / 空，对应"没带自定义头"）
     * @return 按名字不区分大小写排序的脱敏结果；排序是为了让同一份数据每次输出一致，便于比对 diff
     */
    public List<MaskedHeader> mask(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.entrySet().stream()
                .map(e -> new MaskedHeader(e.getKey(), maskedValue(e.getKey(), e.getValue())))
                .sorted(Comparator.comparing(MaskedHeader::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String maskedValue(String name, String value) {
        if (name == null || !PLAIN_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            return MASK;
        }
        if (value == null) {
            return MASK;
        }
        return value.length() <= MAX_PLAIN_LEN ? value : value.substring(0, MAX_PLAIN_LEN) + "…";
    }
}
