package com.rc.notify.config;

import org.springframework.stereotype.Component;

/**
 * 启动自检（任务拆解 T0.3）。
 *
 * <p>把设计文档 §4.3 的参数约束从"文档里的注意事项"变成"启动期的护栏"：
 * 任何一条被破坏直接拒绝启动，绝不允许带病上线。
 * 尤其是约束 1 —— 租约必须显著长于 HTTP 总超时，否则双投率暴涨。
 */
@Component
public class StartupGuard {

    /** lease 必须比 HTTP 总超时多出的安全余量（结果落库 + 调度/时钟抖动）。 */
    static final long LEASE_MARGIN_MS = 5_000;

    public StartupGuard(NotifyProperties props, CircuitProperties circuit) {
        validate(props);
        validateCircuit(circuit, props);
    }

    /** 静态方法便于单元测试（不依赖 Spring 上下文）。 */
    static void validate(NotifyProperties p) {
        require(p != null, "缺少 notify.* 配置");

        var d = p.dispatcher();
        var h = p.http();
        var b = p.backoff();

        // —— 约束 1（§4.3 之一，事故级）：lease 必须 > HTTP 总超时 + 余量 ——
        require(h.connectTimeoutMs() > 0, "notify.http.connect-timeout-ms 必须为正数");
        require(h.totalTimeoutMs() > h.connectTimeoutMs(),
                "notify.http.total-timeout-ms(%d) 必须大于 connect-timeout-ms(%d)"
                        .formatted(h.totalTimeoutMs(), h.connectTimeoutMs()));
        require(d.leaseTtlMs() > h.totalTimeoutMs() + LEASE_MARGIN_MS,
                ("notify.dispatcher.lease-ttl-ms(%d) 必须 > http.total-timeout-ms(%d) + %dms 余量。"
                        + "租约短于投递耗时会直接抬高双投率（设计文档 §4.3 约束 1），拒绝启动")
                        .formatted(d.leaseTtlMs(), h.totalTimeoutMs(), LEASE_MARGIN_MS));

        // —— 约束 2：认领批大小不得超过 worker 池，否则认领后排队等租约过期，自己制造重复 ——
        require(d.workerPoolSize() > 0, "notify.dispatcher.worker-pool-size 必须为正数");
        require(d.batchSizeMax() > 0 && d.batchSizeMax() <= d.workerPoolSize(),
                "notify.dispatcher.batch-size-max(%d) 必须 > 0 且 <= worker-pool-size(%d)"
                        + "（设计文档 §4.3 约束 4），拒绝启动"
                        .formatted(d.batchSizeMax(), d.workerPoolSize()));

        // —— 其余取值范围 ——
        require(d.pollIntervalMs() > 0, "notify.dispatcher.poll-interval-ms 必须为正数");
        require(b.baseMs() > 0, "notify.backoff.base-ms 必须为正数");
        require(b.capMs() >= b.baseMs(), "notify.backoff.cap-ms(%d) 必须 >= base-ms(%d)"
                .formatted(b.capMs(), b.baseMs()));
        require(b.jitter() > 0 && b.jitter() < 1,
                "notify.backoff.jitter(%s) 必须在 (0, 1) 区间".formatted(b.jitter()));
        require(p.retry().maxAttempts() >= 1, "notify.retry.max-attempts 必须 >= 1");
        require(p.backpressure().maxPending() > 0,
                "notify.backpressure.max-pending 必须为正数");
        require(p.backpressure().maxOldestAgeMinutes() > 0,
                "notify.backpressure.max-oldest-age-minutes 必须为正数");
        require(p.backpressure().retryAfterSeconds() >= 1,
                "notify.backpressure.retry-after-seconds 必须 >= 1（0 等于让调用方立刻重试，背压失效）");
    }

    /**
     * 端点隔离与熔断的取值自检（T2.5）。
     *
     * <p>最关键的一条是 {@code maxConcurrentPerEndpoint <= workerPoolSize}：超过的话信号量永远不会
     * 真正拦住任何请求（池里最多就 workerPoolSize 个在途），配置者以为隔离生效了，实际一点没生效——
     * 这种"静默失效"比报错难查得多。
     */
    static void validateCircuit(CircuitProperties c, NotifyProperties p) {
        require(c != null, "缺少 notify.circuit.* 配置");
        if (!c.enabled()) {
            return;
        }
        require(c.windowSeconds() > 0, "notify.circuit.window-seconds 必须为正数");
        require(c.minSamples() > 0, "notify.circuit.min-samples 必须为正数");
        require(c.failureRateThreshold() > 0 && c.failureRateThreshold() <= 1,
                "notify.circuit.failure-rate-threshold(%s) 必须在 (0, 1] 区间"
                        .formatted(c.failureRateThreshold()));
        require(c.openMs() > 0, "notify.circuit.open-ms 必须为正数");
        require(c.halfOpenProbes() >= 1, "notify.circuit.half-open-probes 必须 >= 1");
        int pool = p.dispatcher().workerPoolSize();
        require(c.maxConcurrentPerEndpoint() > 0 && c.maxConcurrentPerEndpoint() <= pool,
                ("notify.circuit.max-concurrent-per-endpoint(%d) 必须 > 0 且 <= worker-pool-size(%d)，"
                        + "否则这个上限永远不会生效（池里最多只有 worker-pool-size 个在途）")
                        .formatted(c.maxConcurrentPerEndpoint(), pool));
        require(c.skipDelaySeconds() >= 1,
                "notify.circuit.skip-delay-seconds 必须 >= 1（0 会形成「认领-退还」热循环）");
        require(c.permitDelaySeconds() >= 1, "notify.circuit.permit-delay-seconds 必须 >= 1");
        require(c.maxTrackedEndpoints() > 0, "notify.circuit.max-tracked-endpoints 必须为正数");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException("[启动自检失败] " + message);
        }
    }
}
