package com.rc.notify.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rc.notify.config.CryptoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * AES-256-GCM 加密存储（T2.4，设计文档 §4.10）——替换 T1.1 的明文占位实现。
 *
 * <p>为什么要加密而不是"数据库本来就只有我们能连"：{@code headers_enc} 里装的是下游供应商的
 * API Key / Bearer Token，一旦落库就是**长期有效、可离线复用**的凭证。备份文件、从库、
 * 慢查询日志、DBA 的一次 SELECT 都可能把它带出去。加密把"拿到数据库"和"拿到凭证"这两件事拆开——
 * 密钥只在进程内存里，来自部署环境注入。
 *
 * <p>存储格式（每列独立成块，{@link #decodeHeaders} 与 {@link #decodeBody} 互相解不开）：
 * <pre>
 *   ┌───────┬───────┬──────────┬──────────────────────┐
 *   │ ver 1B│field1B│  IV 12B  │ ciphertext ‖ tag 16B │
 *   └───────┴───────┴──────────┴──────────────────────┘
 * </pre>
 * <ul>
 *   <li>{@code ver}：留作密钥/算法轮换，现在是 1；</li>
 *   <li>{@code field}：1=headers、2=body，并作为 <b>AAD</b> 参与认证。没有它，
 *       把 headers 的密文挪到 body 列（或反之）也能解密成功，类型就形同虚设；</li>
 *   <li>{@code IV}：每条随机 12 字节。GCM 下同密钥重用 IV 会泄露明文异或值，是致命的，
 *       所以绝不用"固定 IV + 计数器"这种省事写法；</li>
 *   <li>{@code tag}：GCM 自带 128 位认证标签，密文被改动一个字节即解密失败——
 *       这是"防篡改"，加密本身不提供。</li>
 * </ul>
 *
 * <p>Header 的 JSON 序列化用<b>本类私有的 ObjectMapper</b>：这是内部存储格式，
 * 不该随对外契约的命名策略（{@code SNAKE_CASE}）一起变——否则改一个 API 契约字段风格，
 * 就会让历史密文解不出来。
 */
@Component
public class AesGcmPayloadCodec implements PayloadCodec {

    private static final Logger log = LoggerFactory.getLogger(AesGcmPayloadCodec.class);

    /** 仓库内置的开发密钥（base64 的 32 字节）。启动时检测到它就告警，防止被带上生产。 */
    static final String DEV_KEY_B64 = "ZGV2LW9ubHktMzJieXRlLWtleS1kby1ub3QtdXNlISE=";

    private static final byte VERSION = 1;
    private static final byte FIELD_HEADERS = 1;
    private static final byte FIELD_BODY = 2;

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    /** 最短合法密文：版本 + 字段 + IV + tag（空明文也有 tag）。 */
    private static final int MIN_LEN = 2 + IV_BYTES + TAG_BYTES;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final ObjectMapper mapper = new ObjectMapper();

    public AesGcmPayloadCodec(CryptoProperties props) {
        this.key = new SecretKeySpec(decodeKey(props.key()), "AES");
    }

    /**
     * 解析并校验密钥。密钥配错必须拒绝启动——带着错误密钥跑起来，所有新数据都用它加密，
     * 等发现时已经不可逆（旧数据只能用旧密钥解）。
     */
    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "[启动自检失败] notify.crypto.key 未配置。生成方式：openssl rand -base64 32，"
                            + "并通过环境变量 NOTIFY_CRYPTO_KEY 注入");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("[启动自检失败] notify.crypto.key 不是合法的 base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "[启动自检失败] notify.crypto.key 必须是 32 字节（AES-256），当前 %d 字节".formatted(raw.length));
        }
        if (DEV_KEY_B64.equals(base64Key.trim())) {
            log.warn("[安全提示] 正在使用仓库内置的开发密钥，任何人都能解出库里的凭证。"
                    + "生产环境必须通过 NOTIFY_CRYPTO_KEY 注入独立密钥（openssl rand -base64 32）");
        }
        return raw;
    }

    // ——— PayloadCodec ———

    @Override
    public byte[] encodeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return seal(FIELD_HEADERS, mapper.writeValueAsBytes(headers));
        } catch (JsonProcessingException e) {
            // Map<String,String> 不含不可序列化类型，这里属于程序错误
            throw new IllegalStateException("Header 序列化失败", e);
        }
    }

    @Override
    public byte[] encodeBody(String body) {
        return body == null ? null : seal(FIELD_BODY, body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, String> decodeHeaders(byte[] stored) {
        if (stored == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(open(FIELD_HEADERS, stored),
                    mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
        } catch (IOException e) {
            throw new IllegalStateException("Header 反序列化失败（密文已解密，内容不是合法 JSON）", e);
        }
    }

    @Override
    public String decodeBody(byte[] stored) {
        return stored == null ? null : new String(open(FIELD_BODY, stored), StandardCharsets.UTF_8);
    }

    // ——— GCM ———

    private byte[] seal(byte field, byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(new byte[]{VERSION, field});
            byte[] sealed = cipher.doFinal(plain);

            byte[] out = new byte[2 + IV_BYTES + sealed.length];
            out[0] = VERSION;
            out[1] = field;
            System.arraycopy(iv, 0, out, 2, IV_BYTES);
            System.arraycopy(sealed, 0, out, 2 + IV_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密失败（密钥或 JCE 实现异常）", e);
        }
    }

    private byte[] open(byte expectedField, byte[] stored) {
        // 先做结构校验再解密：密文被截断时报"数据损坏"，比抛底层异常更容易定位
        if (stored.length < MIN_LEN) {
            throw new IllegalStateException(
                    "密文长度不足（%d < %d），数据可能被截断".formatted(stored.length, MIN_LEN));
        }
        if (stored[0] != VERSION) {
            throw new IllegalStateException("不支持的密文版本: " + stored[0]);
        }
        if (stored[1] != expectedField) {
            throw new IllegalStateException(
                    "密文字段类型不匹配：期望 %d，实际 %d（header 与 body 的密文不可互换）"
                            .formatted(expectedField, stored[1]));
        }
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(stored, 2, iv, 0, IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(new byte[]{VERSION, expectedField});
            return cipher.doFinal(stored, 2 + IV_BYTES, stored.length - 2 - IV_BYTES);
        } catch (AEADBadTagException e) {
            // 标签校验失败：密钥不对，或密文被改过。两种情况都不该继续——用错误数据投递比不投更糟
            throw new IllegalStateException("解密失败：密钥不匹配或密文被篡改", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("解密失败（JCE 实现异常）", e);
        }
    }
}
