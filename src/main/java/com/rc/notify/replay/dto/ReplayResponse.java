package com.rc.notify.replay.dto;

/**
 * 重放响应（200）：转回队列后的状态快照。
 *
 * <p>预算字段让调用方（运维脚本）不用再查一次就能确认"这次给了多少次机会"。
 */
public record ReplayResponse(
        /** 通知单号。 */
        long notificationNo,
        /** 重放后的状态，恒为 PENDING（回到队列起点，认领条件立即满足）。 */
        String status,
        /** 累计尝试次数——不归零，历史保留。 */
        int attemptCount,
        /** 重放后的预算 = 重放前 attempt_count + 当前档位。 */
        int maxAttempts
) {}
