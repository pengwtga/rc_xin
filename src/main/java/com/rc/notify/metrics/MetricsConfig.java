package com.rc.notify.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标配置（T3.3）：把"tag 基数"这件事从注释里的注意事项变成注册期的硬约束。
 *
 * <p><b>为什么必须限制</b>：{@code endpoint} 的值来自调用方填的 {@code target_url}。
 * 一个写错的循环、一次压测、或者一个恶意的接入方，能轻松造出几十万个不同的 host——
 * 每个 (endpoint, result) 组合都是一个独立的时间序列，Micrometer 的注册表会一路涨到 OOM。
 * 这是"指标把服务打挂"的经典形态，而且发生在最不该出事的时候（流量异常时）。
 *
 * <p><b>为什么是"折叠成 other"而不是"拒绝注册"</b>：拒绝会让超出上限的端点<strong>完全没有数据</strong>，
 * 面板上表现为"这个端点没流量"，比"其他端点"这个桶难看但安全。折叠保留了总量：
 * 全局失败率仍然准确，只是失去了个别端点的分辨率。
 *
 * <p>上限取 200：本系统的端点是"供应商 API"，真实部署在个位数量级；200 已经给了 20 倍余量。
 * 与此对应，{@code notify.circuit.max-tracked-endpoints}（默认 10000）限制的是熔断器的内存，
 * 两者服务不同目的，不要合并。
 */
@Configuration
public class MetricsConfig {

    /** 最多保留多少个不同的 endpoint tag 值。 */
    static final int MAX_ENDPOINT_TAGS = 200;

    /** 超出上限后的折叠值：让总量继续可算，细节让位于内存有界。 */
    static final String OVERFLOW_TAG = "other";

    @Bean
    MeterFilter endpointTagCardinalityLimit() {
        return new MeterFilter() {
            /** 已经见过的 endpoint 值（最多 MAX_ENDPOINT_TAGS 个）。 */
            private final Set<String> known = ConcurrentHashMap.newKeySet();

            @Override
            public Meter.Id map(Meter.Id id) {
                if (!"notify.delivery.attempts".equals(id.getName())) {
                    return id;
                }
                String value = id.getTag("endpoint");
                if (value == null) {
                    return id;
                }
                if (known.contains(value) || known.size() < MAX_ENDPOINT_TAGS) {
                    known.add(value);
                    return id;
                }
                return id.withTag(Tag.of("endpoint", OVERFLOW_TAG));
            }
        };
    }
}
