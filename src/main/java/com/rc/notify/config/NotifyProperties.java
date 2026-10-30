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
            boolean schedulerEnabled,
            long pollIntervalMs,
            int batchSizeMax,
            int workerPoolSize,
            long leaseTtlMs
    ) {}

    /** 出站 HTTP 超时预算（设计文档 §3.3：connect 3s / total 15s）。 */
    public record Http(long connectTimeoutMs, long totalTimeoutMs) {}

    /** 指数退避 + 全抖动（设计文档 §4.4）。delay(n) = min(cap, base * 2^(n-1)) * rand(1-j, 1+j) */
    public record Backoff(long baseMs, long capMs, double jitter) {}

    /** 重试预算（设计文档 §4.4：只花在 RETRYABLE 上，4xx 一次都不花）。 */
    public record Retry(int maxAttempts) {}

    /** 背压阈值（设计文档 §4.8：拒绝优于堆积）。 */
    public record Backpressure(int maxPending, int maxOldestAgeMinutes) {}
}
