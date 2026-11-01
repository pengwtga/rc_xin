package com.rc.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 出站目标的安全策略（T2.4，设计文档 §4.10）。
 *
 * <p>本系统"主动向外发起任意 URL 请求"，天生是 SSRF 放大器：只要能提交一条通知，
 * 就能借我们的网络位置去访问它自己访问不到的东西——云厂商 metadata（169.254.169.254 /
 * 100.100.100.200）、Redis、内部管理后台。
 *
 * @param allowedHosts 完全信任的目标主机：跳过私有段校验、允许明文 http。
 *                     生产应为空；只放内网自建回调（如自建网关的域名/地址）。
 *                     按主机名精确匹配（不区分大小写），不支持通配符——通配符是漏洞的温床。
 * @param requireHttps 是否要求 https。白名单外的目标必须 https：明文 HTTP 会把 Header 里的
 *                     供应商凭证暴露在路径上（代理、日志、镜像流量）。白名单内放行 http，
 *                     因为内网自建服务通常只有 http。
 */
@ConfigurationProperties(prefix = "notify.ssrf")
public record SsrfProperties(List<String> allowedHosts, boolean requireHttps) {

    public SsrfProperties {
        // 配置里写成空字符串（如 ENV 未注入）时，绑定结果可能是 [""]，这里清掉空白项
        allowedHosts = allowedHosts == null
                ? List.of()
                : allowedHosts.stream().filter(h -> h != null && !h.isBlank()).map(String::trim).toList();
    }
}
