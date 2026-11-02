package com.rc.notify.metrics;

import java.time.Instant;

/**
 * 队列健康度的一次采样快照（T3.3，设计文档 §4.8 / §4.11）。
 *
 * <p><b>为什么是"快照"而不是实时查询</b>：这张表同时是投递队列，聚合查询会随行数线性变慢。
 * 采样器每 {@code notify.metrics.sample-interval-ms}（默认 10s）刷一次，监控抓取与受理判定
 * 都读这份缓存——把"监控的读放大"从 O(scrape 频率 × 实例数) 降成 O(1/10s)。
 *
 * <p><b>字段语义，每一个都容易定义错</b>：
 * <ul>
 *   <li>{@code backlog} = PENDING + RETRY_WAIT 的条数，即"还没投出去、且不是终态"的量。
 *       计数封顶在 {@code maxPending + 1}：指标只需要回答"有没有越过阈值"，精确值没有意义，
 *       而封顶让这条 SQL 的代价不随表增长恶化（见 {@link QueueSampler}）；</li>
 *   <li>{@code oldestOverdueSeconds} = <b>已到期但还没被认领</b>的任务里，最早那个逾期了多久。
 *       注意不是"最老任务的创建时间"——退避中的任务本来是未来的，用它算年龄会把正常的长退避
 *       （最多 3.5h 的曲线）误判成积压，进而让背压把一个健康的系统整体拒掉；</li>
 *   <li>{@code processing} = 在途数，正常应 ≤ worker 池大小，持续偏高说明投递在变慢；</li>
 *   <li>{@code dead} = 死信待办量，是"拉取式消费"还没处理完的量，不是故障指标。</li>
 * </ul>
 *
 * @param backlog               PENDING + RETRY_WAIT 条数（封顶 maxPending+1）
 * @param processing            当前 PROCESSING 条数（在途）
 * @param dead                  DEAD 条数（封顶 10001）
 * @param oldestOverdueSeconds  最老到期任务的逾期秒数；无到期任务时为 0
 * @param sampledAt             采样时刻（本机时钟，仅用于判断"这份数据多旧"）
 */
public record QueueStats(
        int backlog,
        int processing,
        int dead,
        long oldestOverdueSeconds,
        Instant sampledAt
) {}
