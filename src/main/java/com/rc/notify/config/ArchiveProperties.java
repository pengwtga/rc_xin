package com.rc.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 归档（PII 最小留存）参数（T3.3，设计文档 §4.10）。
 *
 * <p>两档保留期不是拍脑袋的对称数字，而是两种不同的证据需求：
 * <ul>
 *   <li><b>成功 24h</b>：投递已经完成，请求体的价值只剩"当天排障"。留一天足够定位"下游说没收到"；</li>
 *   <li><b>失败 7d</b>：DEAD 单要支持人工重放（T3.2），重放往往发生在供应商修好之后——
 *       半天、一天都不够。7d 覆盖一个完整的工作周，也是"最长退避曲线 3.5h"的 48 倍。</li>
 * </ul>
 *
 * <p><b>只清密文，不删行</b>：行与 delivery_attempt 是"这条为什么没到"的唯一证据（T3.1 的查询接口）。
 * 归档解决的是 PII 留存，不是存储成本——真到了要删行的量级，那是设计文档 §8 的 V1 演进（按月归档）。
 */
@ConfigurationProperties(prefix = "notify.archive")
public record ArchiveProperties(
        /** 是否启用归档任务；集成测试关闭，改由测试手动调 runOnce()。 */
        boolean enabled,
        /** 执行时刻（cron）：凌晨 3:30 —— 业务低峰，且离"前一天 24h 边界"最近。 */
        String cron,
        /** 成功单的请求体保留时长（小时）：24。 */
        int successRetentionHours,
        /** 失败终态（DEAD/CANCELED）的请求体保留时长（天）：7。 */
        int failureRetentionDays,
        /** 每批处理行数：1000 —— 单条 UPDATE 控制在毫秒级，避免长事务与锁等待。 */
        int batchSize,
        /** 单次运行最多处理批数：100 —— 上限 10 万行/次；清不完就留给下一次，不追求一次清空。 */
        int maxBatchesPerRun
) {}
