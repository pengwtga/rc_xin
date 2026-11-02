package com.rc.notify.query.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 单条通知的完整视图（T3.1）：状态 + 最近一次结果 + 逐次尝试明细。
 *
 * <p><b>刻意不返回的两个东西</b>（设计文档 §4.10 的延伸）：
 * <ul>
 *   <li>{@code body} —— 请求体里是联系人、手机号这类 PII。落库加密就是为了让它不出存储层，
 *       查询接口再吐回去，等于把加密降级成"只防备份文件"；</li>
 *   <li>Header 的<b>原始值</b> —— 见 {@link MaskedHeader}。</li>
 * </ul>
 * 想知道"内容到底对不对"，只能查提交侧的请求日志或让调用方自查。这个取舍有代价（排障时少一个手段），
 * 但反过来"查询接口能拿到明文凭证"的代价更大：任何一个持有 API Key 的人都能读到别人的下游密钥。
 */
public record NotificationDetailResponse(
        /** 通知单号（= notification.id）。 */
        long notificationNo,
        /** 当前状态：PENDING/RETRY_WAIT/PROCESSING/SUCCEEDED/DEAD/CANCELED。 */
        String status,
        /** 调用方自己的幂等键，用于把本单映射回业务事件。 */
        String idempotencyKey,
        /** 目标地址（原文，已过 SSRF 校验；不脱敏——它本就是调用方自己填的）。 */
        String targetUrl,
        /** 大写 HTTP 方法。 */
        String httpMethod,
        /** 累计尝试次数（含被跳过后退还的部分不重复计）。 */
        int attemptCount,
        /** 重试预算；{@code attemptCount >= maxAttempts} 且仍失败即 DEAD。 */
        int maxAttempts,
        /** 下次计划投递时刻（UTC）；DEAD/SUCCEEDED 时是"最后一次计划时刻"，不代表还会再投。 */
        OffsetDateTime nextAttemptAt,
        /** 最近一次 HTTP 状态码；从未拿到响应时为 null。 */
        Integer lastStatusCode,
        /** 最近一次失败类别；成功或从未尝试时为 null。 */
        String lastErrorClass,
        /** 创建时刻（UTC）。 */
        OffsetDateTime createdAt,
        /** 最后一次状态变更时刻（UTC，DB 自动维护）。 */
        OffsetDateTime updatedAt,
        /** 是否携带请求体（只回答有无，不回答内容）。 */
        boolean hasBody,
        /** 脱敏后的请求头；无头或解密失败时为空数组。 */
        List<MaskedHeader> headers,
        /** Header 能否解密：密钥轮换后查老单会变 false，此时其余字段照常可用。 */
        boolean headersDecodable,
        /** 逐次尝试，按 attemptNo 升序；超过上限时保留<b>最近</b>的 N 条。 */
        List<AttemptView> attempts,
        /** 人工重放历史，按时间正序；补上 DEAD → PENDING 这类没有 attempt 明细的状态跳变（T3.2）。 */
        List<ReplayView> replays
) {}
