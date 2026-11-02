/**
 * 可观测性（T3.3，设计文档 §4.11）：回答"现在健康吗"的那一组指标。
 *
 * <p>包内三层，各有明确边界：
 * <ul>
 *   <li>{@link com.rc.notify.metrics.QueueSampler} —— 唯一会查库的地方，定时采样并缓存。
 *       Gauge 只读内存里的原子量，抓取永远不碰数据库；</li>
 *   <li>{@link com.rc.notify.metrics.NotifyMetrics} —— 指标名与 tag 取值的唯一出处，
 *       业务代码只调方法，不拼字符串；</li>
 *   <li>{@link com.rc.notify.metrics.MetricsConfig} —— 注册期的基数约束（endpoint tag 折叠成 other）。</li>
 * </ul>
 *
 * <p>刻意不做的事：分布式追踪（链路只有两跳，单号 + 结构化日志足够，§4.11）、
 * per-endpoint 的 Gauge（端点值来自调用方填的 URL，是无界基数）。
 */
package com.rc.notify.metrics;
