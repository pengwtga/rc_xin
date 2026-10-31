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
        /** 事件级幂等键，建议 {@code <事件类型>:<事件唯一ID>}；同 caller 重复提交返回原单（T1.3）。 */
        @NotBlank(message = "idempotency_key 不能为空")
        @Size(max = 128, message = "idempotency_key 不能超过 128 字符")
        String idempotencyKey,

        /** 外部供应商 API 地址；只校验 http/https 与 host，私有 IP 拦截属 T2.4 SSRF。 */
        @NotBlank(message = "target_url 不能为空")
        @Size(max = 2048, message = "target_url 不能超过 2048 字符")
        String targetUrl,

        /** HTTP 方法；缺省 POST，白名单 POST/PUT/PATCH/DELETE/GET（防方法注入）。 */
        String httpMethod,

        /** 需透传给供应商的请求头（含凭证）；落库前加密，禁 CR/LF，最多 50 项。 */
        Map<String, String> headers,

        /** 已渲染好的请求体原文；可为 null（无体请求），上限 64KB，落库前加密。 */
        String body
) {}
