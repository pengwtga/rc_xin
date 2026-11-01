package com.rc.notify.security;

import com.rc.notify.config.SsrfProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSRF 判定的真值表（T2.4）。
 *
 * <p>这里测的全是"地址段"本身，不依赖网络：IP 字面量与 {@code localhost} 的解析都在本机完成
 * （{@code /etc/hosts}），因此 {@code mvn test} 在任何机器上都稳定。
 *
 * <p>重点在那些<b>看起来无害但能绕过</b>的写法：阿里云 metadata 的 100.100.100.200（CGNAT，
 * JDK 的 isSiteLocalAddress 不认）、{@code 2130706433}（十进制形式的 127.0.0.1）、
 * {@code fc00::/7}（IPv6 ULA，JDK 只认已废弃的 fec0::/10）。
 */
class SsrfGuardTest {

    private static SsrfGuard guard(List<String> allowed, boolean httpsOnly) {
        return new SsrfGuard(new SsrfProperties(allowed, httpsOnly));
    }

    private static SsrfGuard strict() {
        return guard(List.of(), true);
    }

    // ——— 受理层：结构性判定 ———

    @Test
    void 非http协议被拒() {
        assertThat(strict().checkForIngest("ftp://x.com/cb").kind()).isEqualTo(SsrfGuard.Kind.INVALID);
        assertThat(strict().checkForIngest("file:///etc/passwd").kind()).isEqualTo(SsrfGuard.Kind.INVALID);
        assertThat(strict().checkForIngest("gopher://x.com").kind()).isEqualTo(SsrfGuard.Kind.INVALID);
    }

    @Test
    void 缺host被拒() {
        assertThat(strict().checkForIngest("https:///path").kind()).isEqualTo(SsrfGuard.Kind.INVALID);
    }

    @Test
    void 非白名单目标强制https() {
        // 明文 HTTP 会把 Header 里的供应商凭证暴露在链路上
        assertThat(strict().checkForIngest("http://crm.example.com/cb").kind())
                .isEqualTo(SsrfGuard.Kind.INVALID);
        assertThat(strict().checkForIngest("https://crm.example.com/cb").allowed()).isTrue();
        // 白名单内放行 http：内网自建服务通常没有证书
        assertThat(guard(List.of("crm.internal"), true).checkForIngest("http://crm.internal/cb").allowed())
                .isTrue();
    }

    @Test
    void 元数据与内网IP字面量在受理期即被拒() {
        for (String url : new String[]{
                "http://169.254.169.254/latest/meta-data/",   // AWS / 腾讯云 metadata
                "http://100.100.100.200/latest/meta-data/",   // 阿里云 metadata（CGNAT 段）
                "http://127.0.0.1:6379/",
                "http://10.0.0.5/cb",
                "http://172.16.3.4/cb",
                "http://192.168.1.1/cb",
                "http://0.0.0.0/cb",
                "http://224.0.0.1/cb",
                "http://255.255.255.255/cb"}) {
            assertThat(strict().checkForIngest(url).kind())
                    .as(url).isEqualTo(SsrfGuard.Kind.PRIVATE);
        }
    }

    @Test
    void 十进制与简写形式的回环地址同样被拒() {
        // 2130706433 = 127.0.0.1、127.1 是 127.0.0.1 的简写，都是常见的绕过尝试。
        // 结论都是拒绝，但路径不同：JDK 不认十进制形式（直接判非法），认简写形式（判为回环）
        assertThat(strict().checkForIngest("http://2130706433/cb").allowed()).isFalse();
        assertThat(strict().checkForIngest("http://127.1/cb").allowed()).isFalse();
    }

    @Test
    void IPv6回环与唯一本地地址被拒() {
        assertThat(strict().checkForIngest("http://[::1]/cb").kind()).isEqualTo(SsrfGuard.Kind.PRIVATE);
        assertThat(strict().checkForIngest("http://[fc00::1]/cb").kind()).isEqualTo(SsrfGuard.Kind.PRIVATE);
        assertThat(strict().checkForIngest("http://[fe80::1]/cb").kind()).isEqualTo(SsrfGuard.Kind.PRIVATE);
    }

    @Test
    void 公网地址放行() {
        assertThat(strict().checkForIngest("https://8.8.8.8/cb").allowed()).isTrue();
        assertThat(strict().checkForIngest("https://1.1.1.1/cb").allowed()).isTrue();
    }

    @Test
    void 白名单主机跳过地址判定() {
        var g = guard(List.of("127.0.0.1"), true);
        assertThat(g.checkForIngest("http://127.0.0.1:8080/cb").allowed()).isTrue();
        assertThat(g.checkForDelivery("http://127.0.0.1:8080/cb").allowed()).isTrue();
        // 白名单是精确匹配，不做通配：127.0.0.2 不在白名单里，仍然被拒
        assertThat(g.checkForIngest("http://127.0.0.2/cb").kind()).isEqualTo(SsrfGuard.Kind.PRIVATE);
    }

    // ——— 投递层：解析后判定 ———

    @Test
    void 域名解析到回环被拒_localhost是最常见的绕过() {
        // "localhost" 不是 IP 字面量，受理层会放过去；投递层解析出 127.0.0.1 才拦得住
        assertThat(strict().checkForIngest("https://localhost/cb").allowed()).isTrue();
        assertThat(strict().checkForDelivery("https://localhost/cb").kind())
                .isEqualTo(SsrfGuard.Kind.PRIVATE);
    }

    @Test
    void 解析失败的域名归为可重试() {
        // .invalid 是 RFC 2606 保留域，保证不存在；这里要的是"解析失败"而不是"地址非法"
        assertThat(strict().checkForDelivery("https://no-such-host.invalid/cb").kind())
                .isEqualTo(SsrfGuard.Kind.UNRESOLVED);
    }

    @Test
    void 投递层也执行协议与https规则() {
        assertThat(strict().checkForDelivery("http://crm.example.com/cb").kind())
                .isEqualTo(SsrfGuard.Kind.INVALID);
    }
}
