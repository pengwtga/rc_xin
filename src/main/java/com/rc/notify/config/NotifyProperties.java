package com.rc.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部运行参数收口于此（任务拆解 T0.3：配置项收口，代码内无魔法数）。
 *
 * <p>定值依据见《API通知系统_参数定值依据.md》：
 * <ul>
 *   <li>轮询 500ms —— 体验型：SLA p99&lt;5s 中分给队列等待的 ~10%；</li>
 *   <li>超时 3s/15s —— 防御型：对端 P99 的 ~15 倍，宁可误杀不让 worker 挂着；</li>
 *   <li>租约 60s —— 耦合型：lease &gt; totalTimeout + 余量，双投率由 lease/timeout 比值决定；</li>
 *   <li>批大小 —— 耦合型：batchSize &le; 空闲 worker 数，否则自己制造重复投递；</li>
 *   <li>worker 200 —— 容量型：覆盖"下游整体慢 5 倍"的故障态，极端态交给熔断；</li>
 *   <li>重试 10 次 —— 业务型：退避曲线累计覆盖 ~3.5h，等于业务补偿窗口。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "notify")
public record NotifyProperties(
        Dispatcher dispatcher,
        Http http,
        Backoff backoff,
        Retry retry,
        Backpressure backpressure
) {

    /** 投递引擎参数（设计文档 §4.3）。 */
    public record Dispatcher(
            /** 是否启用后台轮询；集成测试置 false，改由测试手动调 pollOnce()。 */
            boolean schedulerEnabled,
            /** 轮询间隔（ms）：500 —— 体验型，SLA p99&lt;5s 中队列等待只占 ~10%。 */
            long pollIntervalMs,
            /** 单轮最大认领条数：100 —— 实际还受空闲 worker 数钳制，取两者较小值。 */
            int batchSizeMax,
            /** 投递线程数：200 —— 一个投递占一个线程，"空闲 worker 数"由线程池维护。 */
            int workerPoolSize,
            /** 租约时长（ms）：60000 —— 必须 &gt; totalTimeoutMs + 余量，否则双投率上升（启动断言）。 */
            long leaseTtlMs
    ) {}

    /** 出站 HTTP 超时预算（设计文档 §3.3：connect 3s / total 15s）。 */
    public record Http(
            /** TCP 握手超时（ms）：3000，正常握手的 30 倍。 */
            long connectTimeoutMs,
            /** 单次请求总超时（ms）：15000，含建连 + 等待 + 读响应；必须大于 connect 超时。 */
            long totalTimeoutMs
    ) {}

    /** 指数退避 + 全抖动（设计文档 §4.4）。delay(n) = min(cap, base * 2^(n-1)) * rand(1-j, 1+j) */
    public record Backoff(
            /** 退避基数（ms）：10000，第 n 次失败等待 base×2^(n-1)。 */
            long baseMs,
            /** 退避上限（ms）：3600000，抖动后最大 1.5×cap。 */
            long capMs,
            /** 抖动比例：0.5 表示 ±50%，全抖动用于打散供应商恢复瞬间的惊群。 */
            double jitter
    ) {}

    /** 重试预算（设计文档 §4.4：只花在 RETRYABLE 上，4xx 一次都不花）。 */
    public record Retry(
            /** 全局重试上限：10，退避曲线累计 ≈3.5h ≈ 业务补偿窗口（调用方可覆盖）。 */
            int maxAttempts
    ) {}

    /** 背压阈值（设计文档 §4.8：拒绝优于堆积）。 */
    public record Backpressure(
            /** 待投递积压上限（条）：100000，≈10 分钟消化量，超过即拒绝新请求。 */
            int maxPending,
            /** 最老待投递任务年龄上限（分钟）：30，超龄说明队列已经不健康，直接拒绝。 */
            int maxOldestAgeMinutes
    ) {}
}
