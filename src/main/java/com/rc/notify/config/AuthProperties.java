package com.rc.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 静态 API Key 表（T1.1）。
 *
 * <p>MVP 选择：接入方个位数，密钥写配置；动态下发/轮换属于控制台范畴，明确不采纳（设计文档 §6.2 第 6 条）。
 * <p>caller_id 由凭证派生而非请求体字段 —— 不可伪造、契约更小、幂等域天然正确（T1.1 文档 §2.2）。
 */
@ConfigurationProperties(prefix = "notify.auth")
public record AuthProperties(List<Client> clients) {

    public AuthProperties {
        clients = clients == null ? List.of() : List.copyOf(clients);
    }

    /** 一个接入方：api-key 与 caller-id 一对一，可覆盖重试预算。 */
    public record Client(
            /** 调用方凭证，请求头 X-Api-Key 携带；仓库内只放占位值，生产走密钥注入。 */
            String apiKey,
            /** 由凭证派生的调用方标识，同时是幂等域、限流与数据隔离的维度（不可由请求体伪造）。 */
            String callerId,
            /** 可选：该调用方的重试预算覆盖值；null 时取 notify.retry.max-attempts。 */
            Integer maxAttempts
    ) {}
}
