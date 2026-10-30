package com.rc.notify.ingest.dto;

/**
 * 提交响应（202 Accepted）：单号 + 初始状态。
 *
 * <p>notification_no 直接暴露自增 id（设计文档 §4.1：内部系统 + API Key 鉴权，无枚举风险，
 * 不引入 ULID 等第二套标识）。
 */
public record SubmitNotificationResponse(long notificationNo, String status) {}
