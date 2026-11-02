package com.rc.notify.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 指标口径的唯一出处（T3.3）：所有 metric 名与 tag 取值都收敛在这里，业务代码只调方法。
 *
 * <p>散落的 {@code registry.counter("xxx", ...)} 迟早长出三套拼写（{@code notify.delivery.attempt}
 * / {@code notify.deliveries} / {@code delivery_attempts}），面板与告警全部对不上。
 *
 * <p><b>指标设计的三条口径</b>（对应设计文档 §4.11 的三个问题）：
 * <ul>
 *   <li><b>健康吗</b>：{@code notify.queue.*}（深度 / 最老逾期 / 死信）、
 *       {@code notify.ingest.requests}（QPS 与错误率）、{@code notify.delivery.attempts}（成功率）、
 *       {@code notify.circuit.open.endpoints}；</li>
 *   <li><b>这条为什么没到</b>：不靠指标，靠单号查询（T3.1 的逐次明细）；</li>
 *   <li><b>是不是某供应商的问题</b>：{@code notify.delivery.attempts} 的 {@code endpoint} tag，
 *       失败率按端点聚合即可区分单点故障与全局问题。</li>
 * </ul>
 *
 * <p><b>刻意不给"成功率"建 Gauge</b>：成功率必须选一个时间窗口，而窗口是查询时的事。
 * 存原始计数（success / retryable / permanent），窗口交给 PromQL 的 {@code rate()}——
 * 否则换一个窗口（5m 还是 1h）就得改代码、发版。
 */
@Component
public class NotifyMetrics {

    private final MeterRegistry registry;

    public NotifyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 受理接口的每次响应，按状态码计数。
     *
     * <p>用状态码而不是"业务原因"作 tag：它是 HTTP 层唯一不会说谎的事实，也是调用方真正看到的东西。
     * 具体是"背压拒绝"还是"参数非法"，看下面两个计数器，不要在这里堆语义 tag。
     */
    public void ingestRequest(String route, int status) {
        registry.counter("notify.ingest.requests", "route", route, "status", String.valueOf(status)).increment();
    }

    /**
     * 幂等命中（T1.3）。
     *
     * <p>它不是错误：调用方超时重试是正常路径。但它同时说明两件事——
     * 去重机制在起作用，以及"有人正在重试"（持续上涨往往意味着我们的响应慢了）。
     */
    public void idempotentHit(String caller) {
        registry.counter("notify.ingest.idempotent.hits", "caller", caller).increment();
    }

    /** 背压拒绝（T3.3）：队列不健康时明确说"现在处理不了"，比收下后无限延迟更负责任（§4.8）。 */
    public void backpressureRejected(String reason) {
        registry.counter("notify.ingest.backpressure.rejections", "reason", reason).increment();
    }

    /**
     * 一次真实投递的结果（跳过与过期结果不算）。
     *
     * @param endpoint {@code scheme://host:port}；无法解析时为 {@code unknown}
     * @param result   {@code success} / {@code retryable} / {@code permanent}
     */
    public void deliveryAttempt(String endpoint, String result) {
        registry.counter("notify.delivery.attempts",
                "endpoint", endpoint == null ? "unknown" : endpoint,
                "result", result).increment();
    }

    /**
     * 被跳过（熔断中 / 端点并发满）而退还的任务。
     *
     * <p>与失败样本分开计数是必须的：跳过是"我们主动不发"（下游故障或自我保护），
     * 失败是"发了但对方不行"。把两者混进一个失败率里，熔断打开时失败率反而会下降。
     */
    public void deliverySkipped(String reason) {
        deliverySkipped(reason, 1);
    }

    /** 批量退还形态（熔断时一轮可能退几十条），一次自增到位。 */
    public void deliverySkipped(String reason, int count) {
        if (count > 0) {
            registry.counter("notify.delivery.skipped", "reason", reason).increment(count);
        }
    }

    /**
     * 结果被租约守卫丢弃（T2.2）。
     *
     * <p>正常应该是 0。非 0 意味着本实例的投递耗时逼近了租约，或本实例曾被判定死亡——
     * 这是"双投正在发生"的直接证据，值得单独告警。
     */
    public void staleResult() {
        registry.counter("notify.delivery.stale.results").increment();
    }

    /** 宣布死信（T2.3），按原因分组：PERMANENT 是"对方拒收"，BUDGET_EXHAUSTED 是"我们花完了预算"。 */
    public void deadLetter(String reason) {
        registry.counter("notify.deadletter", "reason", reason).increment();
    }

    /** 归档任务清掉的行数（T3.3），按档位分组：SUCCEEDED 24h / DEAD+CANCELED 7d。 */
    public void archiveCleared(String tier, int rows) {
        if (rows > 0) {
            registry.counter("notify.archive.cleared", "tier", tier).increment(rows);
        }
    }
}
