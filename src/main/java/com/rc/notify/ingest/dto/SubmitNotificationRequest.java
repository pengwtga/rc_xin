package com.rc.notify.ingest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 提交通知请求体（T1.1 接口契约 §3.1）。
 *
 * <p>只做结构校验（非空/长度）；业务校验（URL 协议、method 白名单、Header CRLF、体积）
 * 在 {@code NotificationService} 里，因为它需要组合字段间的关系。
 *
 * <p>注意：请求体不含 caller_id —— 由 API Key 派生（T1.1 文档 §2.2）。
 */
public record SubmitNotificationRequest(
        @NotBlank(message = "idempotency_key 不能为空")
        @Size(max = 128, message = "idempotency_key 不能超过 128 字符")
        String idempotencyKey,

        @NotBlank(message = "target_url 不能为空")
        @Size(max = 2048, message = "target_url 不能超过 2048 字符")
        String targetUrl,

        String httpMethod,

        Map<String, String> headers,

        String body
) {}
