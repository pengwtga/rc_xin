package com.rc.notify.query.dto;

import java.time.OffsetDateTime;

/**
 * 一次人工重放的审计视图（T3.2）。
 *
 * <p>它补上状态轨迹的一类空洞（T3.1 §6.1）：DEAD → PENDING 的跳变没有 attempt 明细，
 * 原因在这里——谁、为什么、当时给多少预算。
 */
public record ReplayView(
        /** 执行重放的操作人（缺省为调用方标识）。 */
        String operator,
        /** 重放原因（人工填写，必填）。 */
        String reason,
        /** 重放时的累计尝试次数快照。 */
        int attemptCountBefore,
        /** 重放后的预算。 */
        int maxAttemptsAfter,
        /** 重放时刻（UTC）。 */
        OffsetDateTime replayedAt
) {}
