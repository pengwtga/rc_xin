package com.rc.notify.ingest.dto;

/**
 * 提交响应（202 Accepted）：单号 + 初始状态。
 *
 * <p>notification_no 直接暴露自增 id（设计文档 §4.1：内部系统 + API Key 鉴权，无枚举风险，
 * 不引入 ULID 等第二套标识）。
 */
public record SubmitNotificationResponse(
        /** 通知单号（= notification.id），后续查询与排障都用它。 */
        long notificationNo,
        /** 提交时刻的状态快照：首次提交为 PENDING，幂等命中时为原单当前状态。 */
        String status
) {}
