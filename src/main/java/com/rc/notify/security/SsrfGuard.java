package com.rc.notify.security;

import com.rc.notify.config.SsrfProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 出站目标校验（T2.4，设计文档 §4.10）——本系统唯一的 SSRF 防线。
 *
 * <p>分两次调用，职责不同（T2.4 文档 §2.1）：
 * <ul>
 *   <li>{@link #checkForIngest}：受理请求时，只做<b>不需要网络</b>的判定——协议、
 *       主机形态、IP 字面量落段。目的是把"立刻能判定的错误"在 422 里返回给调用方；</li>
 *   <li>{@link #checkForDelivery}：真正投递前，<b>重新解析域名</b>并校验解析出的每一个 IP。
 *       这才是防线：域名随时可以改指向（DNS rebinding），受理时的判断到投递时可能已失效。</li>
 * </ul>
 *
 * <p>为什么受理时不解析域名：受理路径的可用性不该依赖 DNS。域名解析抖动时，调用方得到的
 * 会是 422（看起来像"你填错了"），实际是"我们查不了 DNS"。把解析留给投递期，受理接口
 * 保持零外部依赖（受理 p99 &lt; 50ms 的前提）。
 *
 * <p>判定原则是 <b>fail closed</b>：一个域名解析出多个 IP 时，只要有一个落在禁止段就整体拒绝。
 * 否则攻击者可以让 DNS 交替返回公网与内网地址，"一半概率打中"就够用了。
 */
@Component
public class SsrfGuard {

    /** 拒绝原因。OK 之外的三类在两层里的处置不同，见 {@code Dispatcher} 与 {@code NotificationService}。 */
    public enum Kind {
        /** 通过。 */
        OK,
        /** URL 结构不合法 / 协议不允许 / 非白名单目标用了 http。 */
        INVALID,
        /** 解析结果落在禁止网段（回环、私有、链路本地、CGNAT、保留…），或干脆解析不出来但必须保守处理。 */
        PRIVATE,
        /** 域名解析失败（DNS 抖动）。只可能出现在投递层，且按可重试处理。 */
        UNRESOLVED
    }

    /**
     * 校验结论。
     *
     * @param kind   结论
     * @param detail 面向人的说明（进 422 的 message 或投递日志），不含敏感信息
     */
    public record Verdict(Kind kind, String detail) {
        public static final Verdict OK = new Verdict(Kind.OK, "ok");

        public boolean allowed() {
            return kind == Kind.OK;
        }
    }

    /** 白名单：精确匹配，小写归一。 */
    private final Set<String> allowedHosts;
    private final boolean requireHttps;

    public SsrfGuard(SsrfProperties props) {
        Set<String> set = new HashSet<>();
        for (String h : props.allowedHosts()) {
            set.add(h.toLowerCase(Locale.ROOT));
        }
        this.allowedHosts = Set.copyOf(set);
        this.requireHttps = props.requireHttps();
    }

    // ——— 受理层：不碰网络 ———

    /**
     * 受理时校验。不解析域名，因此对 {@code https://crm.example.com} 这类目标零网络开销。
     *
     * <p>注意这里**不是**安全防线：DNS 变体写法（{@code 2130706433}、{@code 0x7f000001}）与
     * 解析后才暴露的内网地址都会放过去，由投递层兜住。这里的唯一职责是"尽早给出可读的 422"。
     */
    public Verdict checkForIngest(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            return new Verdict(Kind.INVALID, "不是合法 URL: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return new Verdict(Kind.INVALID, "只支持 http/https，当前: " + scheme);
        }
        String host = bareHost(uri.getHost());
        if (host == null || host.isBlank()) {
            return new Verdict(Kind.INVALID, "缺少 host");
        }
        // 地址判定先于协议判定：这是严重性排序，不是风格问题。
        // 对 http://169.254.169.254 说"请使用 https"是误导——换成 https 打 metadata 一样危险
        if (!allowed(host) && isIpLiteralShape(host)) {
            try {
                // getByName 对 IP 字面量不查 DNS，纯本地解析
                if (isBlocked(InetAddress.getByName(host))) {
                    return new Verdict(Kind.PRIVATE, "目标地址属于禁止网段（回环/私有/链路本地/保留）: " + host);
                }
            } catch (UnknownHostException e) {
                return new Verdict(Kind.INVALID, "IP 字面量无法解析: " + host);
            }
        }
        if (!allowed(host) && requireHttps && scheme.equalsIgnoreCase("http")) {
            return new Verdict(Kind.INVALID, "非白名单目标必须使用 https（明文 http 会暴露凭证）: " + host);
        }
        return Verdict.OK;
    }

    // ——— 投递层：解析并校验每一个 IP ———

    /**
     * 投递前校验：解析域名并逐个检查解析结果。
     *
     * <p>这是真正的防线，也是 DNS rebinding 的缓解手段——虽然仍有"校验后、连接前"的窗口
     * （见 T2.4 文档 §6 已知限制），但把窗口从"落库到投递"（可能是几小时）压到毫秒级。
     */
    public Verdict checkForDelivery(String rawUrl) {
        Verdict structural = checkForIngest(rawUrl);
        if (!structural.allowed()) {
            return structural;
        }
        String host = newHost(rawUrl);
        if (allowed(host)) {
            return Verdict.OK;
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return new Verdict(Kind.UNRESOLVED, "域名解析失败: " + host);
        } catch (SecurityException e) {
            // 被 SecurityManager 拦下时按最坏情况处理，不放行
            return new Verdict(Kind.PRIVATE, "目标地址无法校验: " + host);
        }
        for (InetAddress addr : resolved) {
            if (isBlocked(addr)) {
                return new Verdict(Kind.PRIVATE,
                        "解析到禁止网段: " + host + " -> " + addr.getHostAddress());
            }
        }
        return Verdict.OK;
    }

    // ——— 判定细节 ———

    private boolean allowed(String host) {
        return allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    /** {@code URI.getHost()} 对 IPv6 返回 {@code [::1]} 形式，解析前要去掉方括号。 */
    private static String bareHost(String host) {
        if (host == null) {
            return null;
        }
        if (host.length() > 1 && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static String newHost(String rawUrl) {
        return bareHost(URI.create(rawUrl).getHost());
    }

    /**
     * 判断 host 是不是一个"不需要 DNS 就能解析"的 IP 字面量。
     *
     * <p>只认严格形态（点分四段 / 纯十进制 / 带冒号的 IPv6）。{@code 0x7f000001} 这类老式变体
     * 故意不在这里判定——它们在投递层会被 {@code getAllByName} 解析成真实 IP 再校验，
     * 而受理层多做一次猜测只会把"不查 DNS"这条纪律弄脏。
     */
    static boolean isIpLiteralShape(String host) {
        if (host.indexOf(':') >= 0) {
            return true;                                  // IPv6 字面量（含 [::1] 里的 ::1）
        }
        if (host.matches("\\d+")) {
            return true;                                  // 2130706433 = 127.0.0.1
        }
        return host.matches("\\d{1,3}(\\.\\d{1,3}){1,3}"); // 127.0.0.1 / 127.1 / 1.2.3.4
    }

    /**
     * 地址是否禁止访问。
     *
     * <p>先用 JDK 的分类方法覆盖大部分情况，再补它没覆盖的：CGNAT（100.64/10，阿里云 metadata
     * {@code 100.100.100.200} 就在里面）、0.0.0.0/8、240/4 保留段、IPv6 ULA（fc00::/7，
     * JDK 的 isSiteLocalAddress 只认已废弃的 fec0::/10），以及 IPv4-mapped 的 IPv6 形式
     * （{@code ::ffff:127.0.0.1}，不特殊处理就是一条绕过路径）。
     */
    static boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            return blockedV4(b[0] & 0xFF, b[1] & 0xFF);
        }
        // IPv4-mapped IPv6：::ffff:a.b.c.d —— Java 通常会折叠成 Inet4Address，但不保证
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                mapped = false;
                break;
            }
        }
        if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            return blockedV4(b[12] & 0xFF, b[13] & 0xFF);
        }
        // fc00::/7 唯一本地地址
        return (b[0] & 0xFE) == 0xFC;
    }

    /** IPv4 的补充判定（JDK 已覆盖回环/私有/链路本地，这里只补它漏的段）。 */
    private static boolean blockedV4(int first, int second) {
        if (first == 0) {
            return true;                                        // 0.0.0.0/8 本网络
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return true;                                        // 100.64.0.0/10 CGNAT
        }
        return first >= 240;                                    // 240.0.0.0/4 保留 + 广播
    }
}
