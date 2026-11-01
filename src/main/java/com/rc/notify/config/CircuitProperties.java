package com.rc.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 端点隔离与熔断（T2.5，设计文档 §4.5）。
 *
 * <p>为什么需要：投递是<b>阻塞式</b>的（一个投递占一个线程）。一个供应商变慢，200 个 worker 会被它
 * 逐个占满，其他供应商的任务全部排队——故障从"一个下游慢"扩散成"整个系统慢"。需求里"不同供应商"
 * 四个字给的正是这个信号。
 *
 * <p>两层防护，强度不同：
 * <ul>
 *   <li><b>并发上限</b>（{@code maxConcurrentPerEndpoint}）——在故障早期就生效，不需要判断"是否故障"，
 *       只保证一个端点最多占这么多 worker，剩下的 worker 留给别人；</li>
 *   <li><b>熔断器</b>（{@code windowSeconds / minSamples / failureRateThreshold / openMs}）——
 *       在故障被确认后生效，作用是<b>停止无效尝试并保住重试预算</b>（不是限流）。</li>
 * </ul>
 *
 * @param enabled                是否启用隔离与熔断。关掉只用于对照实验，生产不要关
 * @param windowSeconds          失败率统计窗口：60s。够长到能覆盖一次供应商抖动，够短到能在几分钟内恢复
 * @param minSamples             触发熔断所需的最小样本数：20。样本太少时"2 次里 2 次失败"就熔断，噪声会变成故障
 * @param failureRateThreshold   失败率阈值：0.5。超过即 OPEN；不用 1.0 是因为"全部失败"时已经浪费了 20 次请求
 * @param openMs                 OPEN 持续时长：60s。与窗口同量级——短于此还没等对方恢复就再打一轮，长了恢复慢
 * @param halfOpenProbes         HALF_OPEN 允许的并发探测数：1。只放一个探针，成功才放行全量，避免恢复瞬间的惊群
 * @param maxConcurrentPerEndpoint 单端点并发上限：16。200 worker / 约 12 个活跃端点，给单端点留出 8 倍余量
 * @param skipDelaySeconds       被跳过的任务推迟多久：30s。不能是 0——否则下一轮 poll（500ms 后）立刻重新认领，
 *                               形成"认领-退还"热循环，而这些任务本来就不可能立刻投出去
 * @param permitDelaySeconds     并发上限满时推迟多久：2s。这不是故障，只是排队，短延迟让它尽快回来
 * @param maxTrackedEndpoints    内存里最多跟踪多少个端点：10000。端点键来自 URL，不设上限就是一条内存耗尽的路径
 */
@ConfigurationProperties(prefix = "notify.circuit")
public record CircuitProperties(
        boolean enabled,
        int windowSeconds,
        int minSamples,
        double failureRateThreshold,
        long openMs,
        int halfOpenProbes,
        int maxConcurrentPerEndpoint,
        int skipDelaySeconds,
        int permitDelaySeconds,
        int maxTrackedEndpoints
) {}
