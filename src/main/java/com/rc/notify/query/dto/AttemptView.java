package com.rc.notify.query.dto;

import java.time.OffsetDateTime;

/**
 * 一次投递尝试（T3.1）—— 对应一行 {@code delivery_attempt}。
 *
 * <p>这组字段是回答"这条为什么没到"的全部材料（设计文档 §4.11）：状态码说明对方怎么回的，
 * 耗时说明卡在哪一层，错误分类说明我们的判定，响应片段说明对方说了什么。
 */
public record AttemptView(
        /** 第几次尝试，1 起；与 {@code notification.attempt_count} 同源，跨重放继续递增。 */
        int attemptNo,
        /** 本次投递开始时刻（UTC）。 */
        OffsetDateTime startedAt,
        /** 本次投递耗时（ms）；网络层超时约等于总超时预算，用它判断是"慢"还是"拒"。 */
        Long durationMs,
        /** HTTP 状态码；网络层失败（连接被拒、超时、TLS 失败）时为 null。 */
        Integer statusCode,
        /** 失败类别（TIMEOUT/DNS/TLS/CONN_REFUSED/IO/HTTP_3XX/4XX/5XX/SSRF_BLOCKED）；成功为 null。 */
        String errorClass,
        /** 响应体片段，落库时截断到 2048 字符，展示时再截到 512 —— 见 T3.1 文档 §2.6。 */
        String responseSnippet
) {}
