package com.rc.notify.ingest;

import com.rc.notify.api.ApiException;
import com.rc.notify.config.NotifyProperties;
import com.rc.notify.metrics.NotifyMetrics;
import com.rc.notify.metrics.QueueStats;
import com.rc.notify.metrics.QueueStatsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 接入侧背压（T3.3，设计文档 §4.8）：队列不健康时，明确说"现在处理不了"。
 *
 * <p><b>为什么必须拒绝而不是照收</b>：这个系统的受理成本极低（一次 INSERT）、完成成本极高（依赖外部）。
 * 没有背压它就是无限缓冲区：上游看到 202 以为送达了，实际可能几小时后才投出去——
 * 而"几小时后才到的通知"在业务上等于没到。**明确失败可重试，比虚假的成功更负责任。**
 *
 * <p><b>四个刻意的边界</b>：
 * <ol>
 *   <li><b>只挡新提交</b>，不挡查询与重放。系统过载时运维恰恰需要查单、需要重放，
 *       把管理面也挡住等于事故时自断手脚；</li>
 *   <li><b>fail-open</b>：从未采样成功（刚启动 / DB 刚恢复）时放行。此时提交本身也会失败，
 *       不会静默堆积；而反过来"拒绝一切"会让一次监控故障升级成全站不可用；</li>
 *   <li><b>读缓存不查库</b>：受理 p99 &lt; 50ms 的前提之一（参数依据 §3.2）。快照最多旧 10s，
 *       相对 30 分钟的阈值可以忽略；</li>
 *   <li><b>阈值两个，先看年龄</b>：积压条数受吞吐影响（同样的 10 万条，worker 多就消化得快），
 *       而"最老到期任务等了多久"直接就是送达延迟——它是更能反映 SLA 的那个数。</li>
 * </ol>
 *
 * <p><b>不做 per-caller 限流</b>：那是公平性问题（一个调用方打满不该让别人排队），
 * 与"系统还能不能接"是两个维度，见任务清单 T3.5。
 */
@Component
public class BackpressureGuard {

    private static final Logger log = LoggerFactory.getLogger(BackpressureGuard.class);

    /** 拒绝日志的节流窗口：过载时每个请求都触发，日志本身会变成第二个故障。 */
    private static final long LOG_INTERVAL_MS = 60_000;

    private final QueueStatsSource source;
    private final NotifyProperties.Backpressure cfg;
    private final NotifyMetrics metrics;

    /** 上次打拒绝日志的时刻（毫秒）。 */
    private final AtomicLong lastLogAt = new AtomicLong();

    public BackpressureGuard(QueueStatsSource source, NotifyProperties props, NotifyMetrics metrics) {
        this.source = source;
        this.cfg = props.backpressure();
        this.metrics = metrics;
    }

    /**
     * 受理前调用：队列不健康时抛 503（带 Retry-After）。
     *
     * @throws ApiException 503 QUEUE_OVERLOADED
     */
    public void ensureCapacity() {
        QueueStats stats = source.current();
        if (stats == null) {
            // 从未采样成功：没有信息就不做判断。见类注释第 2 条
            return;
        }

        long maxOldestSeconds = cfg.maxOldestAgeMinutes() * 60L;
        String reason;
        String detail;
        if (stats.oldestOverdueSeconds() >= maxOldestSeconds) {
            reason = "oldest_overdue";
            detail = "队列积压（最老到期任务已等待 %d 秒，阈值 %d 秒），请 %d 秒后重试"
                    .formatted(stats.oldestOverdueSeconds(), maxOldestSeconds, cfg.retryAfterSeconds());
        } else if (stats.backlog() > cfg.maxPending()) {
            reason = "backlog";
            detail = "队列积压（待投递 %d 条，阈值 %d 条），请 %d 秒后重试"
                    .formatted(stats.backlog(), cfg.maxPending(), cfg.retryAfterSeconds());
        } else {
            return;
        }

        metrics.backpressureRejected(reason);
        logIfDue(reason, stats);
        throw ApiException.overloaded(detail, cfg.retryAfterSeconds());
    }

    /** 一分钟最多一条 WARN：拒绝的原因与数值在日志里能看懂，频率交给指标去表达。 */
    private void logIfDue(String reason, QueueStats stats) {
        long now = System.currentTimeMillis();
        long last = lastLogAt.get();
        if (now - last >= LOG_INTERVAL_MS && lastLogAt.compareAndSet(last, now)) {
            log.warn("背压触发（{}）：backlog={}, oldestOverdue={}s, processing={}；新提交返回 503 + Retry-After {}s",
                    reason, stats.backlog(), stats.oldestOverdueSeconds(), stats.processing(),
                    cfg.retryAfterSeconds());
        }
    }
}
