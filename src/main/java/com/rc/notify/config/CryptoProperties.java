package com.rc.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 凭证与请求体的加密配置（T2.4，设计文档 §4.10）。
 *
 * @param key AES-256-GCM 主密钥，base64 编码的 32 字节。生产由部署环境注入
 *            （{@code NOTIFY_CRYPTO_KEY}），绝不入库、绝不进日志。
 *            生成方式：{@code openssl rand -base64 32}。
 */
@ConfigurationProperties(prefix = "notify.crypto")
public record CryptoProperties(String key) {}
