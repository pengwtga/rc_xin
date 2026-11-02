package com.rc.notify.metrics;

/**
 * 队列健康度快照的来源（T3.3）。
 *
 * <p>抽成接口只有一个目的：让背压判定可以脱开数据库单独测试。
 * 生产实现是 {@link QueueSampler}（定时采样 + 缓存），测试里可以直接给一个固定值。
 */
@FunctionalInterface
public interface QueueStatsSource {

    /**
     * @return 最近一次成功采样的快照；<b>null = 启动以来从未采到过</b>（而不是"队列为空"）。
     *         调用方必须把 null 当作"没有信息"，而不是"一切正常"或"一切异常"。
     */
    QueueStats current();
}
