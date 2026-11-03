package com.rc.notify.metrics;

import com.rc.notify.config.NotifyProperties;
import com.rc.notify.dispatcher.Dispatcher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 投递线程池的实时占用（T4.2 压测需要"worker 利用率"这个口径）。
 *
 * <p>为什么不复用 {@code notify.queue.processing}：那个值是 {@link QueueSampler} 每 10s 查库采到的
 * 快照，反映的是"库里处于 PROCESSING 的行数"，与"此刻有多少线程在干活"差着采样周期与
 * 事务时序。压测要回答的是"200 个 worker 够不够"，必须看即时值。
 *
 * <p>这里读的两个数都由 JVM 免费维护（{@code ThreadPoolExecutor#getActiveCount} 与池大小），
 * 抓取时只是一次字段读取，不触碰数据库——与"抓取不许查库"的口径一致。
 *
 * <p>分开两个指标而不是直接给一个"利用率"：利用率的分母（池大小）和分子（在途数）在
 * 扩容/缩容的瞬间会同时变，先存原始值、需要时在 PromQL 里除，才看得见那次变更。
 */
@Component
public class DispatchMetricsBinder {

    public DispatchMetricsBinder(Dispatcher dispatcher, NotifyProperties props, MeterRegistry registry) {
        Gauge.builder("notify.dispatch.workers.active", dispatcher, Dispatcher::activeWorkers)
                .description("此刻正在投递的 worker 数").register(registry);
        Gauge.builder("notify.dispatch.workers.size", props,
                        p -> p.dispatcher().workerPoolSize())
                .description("投递线程池大小（配置值），利用率 = active / size").register(registry);
    }
}
