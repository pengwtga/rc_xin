package com.rc.notify.query.dto;

import java.time.OffsetDateTime;

/**
 * 列表里的一条（T3.1）：不含 attempts、不含 header —— 列表是用来"扫"的，详情才是用来"查"的。
 *
 * <p>体积是硬约束：一页 100 条若各带 10 条明细与 2KB 响应片段，单次响应就是 2MB，
 * 而列表场景（死信巡检、批量重放前的核对）只需要知道"哪几条、什么状态、错在哪一类"。
 */
public record NotificationSummary(
        /** 通知单号。 */
        long notificationNo,
        /** 当前状态。 */
        String status,
        /** 调用方自己的幂等键。 */
        String idempotencyKey,
        /** 目标地址。 */
        String targetUrl,
        /** 大写 HTTP 方法。 */
        String httpMethod,
        /** 累计尝试次数。 */
        int attemptCount,
        /** 重试预算。 */
        int maxAttempts,
        /** 下次计划投递时刻（UTC）。 */
        OffsetDateTime nextAttemptAt,
        /** 最近一次 HTTP 状态码。 */
        Integer lastStatusCode,
        /** 最近一次失败类别。 */
        String lastErrorClass,
        /** 创建时刻（UTC）。 */
        OffsetDateTime createdAt,
        /** 最后一次状态变更时刻（UTC）。 */
        OffsetDateTime updatedAt
) {}
