package com.rc.notify.store;

import com.rc.notify.config.CryptoProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AES-256-GCM 存储格式的验收（T2.4）。
 *
 * <p>四条不变量，缺一条这套加密就是装饰：
 * <ol>
 *   <li>往返一致（含中文 body）；</li>
 *   <li>密文里不含明文（不是"看起来像乱码"，是真的搜不到）；</li>
 *   <li>同明文两次加密输出不同（IV 随机，否则相同请求会得到相同密文，等于泄露"这两条相同"）；</li>
 *   <li>改一个字节就解不开（GCM 的认证标签，防篡改）。</li>
 * </ol>
 */
class AesGcmPayloadCodecTest {

    private static final String DEV_KEY = "ZGV2LW9ubHktMzJieXRlLWtleS1kby1ub3QtdXNlISE=";

    private final AesGcmPayloadCodec codec = new AesGcmPayloadCodec(new CryptoProperties(DEV_KEY));

    private static Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer sk_live_SECRET_TOKEN");
        h.put("X-Sign", "abc123");
        return h;
    }

    @Test
    void header与body往返一致() {
        assertThat(codec.decodeHeaders(codec.encodeHeaders(headers()))).isEqualTo(headers());
        assertThat(codec.decodeBody(codec.encodeBody("{\"订单\":\"已支付\",\"金额\":128.5}")))
                .isEqualTo("{\"订单\":\"已支付\",\"金额\":128.5}");
    }

    @Test
    void 空值保持空值() {
        assertThat(codec.encodeHeaders(null)).isNull();
        assertThat(codec.encodeHeaders(Map.of())).isNull();
        assertThat(codec.encodeBody(null)).isNull();
        assertThat(codec.decodeHeaders(null)).isEmpty();
        assertThat(codec.decodeBody(null)).isNull();
    }

    @Test
    void 密文中搜不到明文凭证() {
        byte[] enc = codec.encodeHeaders(headers());
        assertThat(new String(enc, StandardCharsets.ISO_8859_1)).doesNotContain("sk_live_SECRET_TOKEN");
        assertThat(new String(enc, StandardCharsets.ISO_8859_1)).doesNotContain("Authorization");

        byte[] encBody = codec.encodeBody("id_card=310101199001011234");
        assertThat(new String(encBody, StandardCharsets.ISO_8859_1)).doesNotContain("310101199001011234");
    }

    @Test
    void 同一明文两次加密结果不同_IV必须随机() {
        byte[] a = codec.encodeBody("same payload");
        byte[] b = codec.encodeBody("same payload");
        assertThat(a).isNotEqualTo(b);
        assertThat(codec.decodeBody(a)).isEqualTo(codec.decodeBody(b));
    }

    @Test
    void 密文被改动一个字节即解不开() {
        byte[] enc = codec.encodeBody("hello");
        enc[enc.length - 1] ^= 0x01;
        assertThatThrownBy(() -> codec.decodeBody(enc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void header与body的密文不可互换_AAD挡住整列搬运() {
        // 没有 AAD 的话，把 headers 的密文挪进 body_enc 也能解出一种"合法"的结果
        byte[] encHeaders = codec.encodeHeaders(headers());
        assertThatThrownBy(() -> codec.decodeBody(encHeaders))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("字段类型不匹配");
    }

    @Test
    void 别的密钥解不开() {
        var other = new AesGcmPayloadCodec(
                new CryptoProperties("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        byte[] enc = codec.encodeBody("secret");
        assertThatThrownBy(() -> other.decodeBody(enc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥不匹配");
    }

    @Test
    void 密钥配置错误必须启动失败() {
        assertThatThrownBy(() -> new AesGcmPayloadCodec(new CryptoProperties(null)))
                .hasMessageContaining("NOTIFY_CRYPTO_KEY");
        assertThatThrownBy(() -> new AesGcmPayloadCodec(new CryptoProperties("not-base64!!")))
                .hasMessageContaining("base64");
        // 16 字节的 AES-128 密钥不接受：算法强度由配置决定，不允许"降级也能跑"
        assertThatThrownBy(() -> new AesGcmPayloadCodec(
                new CryptoProperties("MDEyMzQ1Njc4OWFiY2RlZg==")))
                .hasMessageContaining("32 字节");
    }

    @Test
    void 截断密文报数据损坏而不是底层异常() {
        byte[] enc = codec.encodeBody("hello");
        // 截到比最短合法长度还短：这已经不是密文，先报"数据损坏"比抛底层解密异常更容易定位
        byte[] truncated = new byte[2 + 12 + 16 - 1];
        System.arraycopy(enc, 0, truncated, 0, truncated.length);
        assertThatThrownBy(() -> codec.decodeBody(truncated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密文长度不足");
    }
}
