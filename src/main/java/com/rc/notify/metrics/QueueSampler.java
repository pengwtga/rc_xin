package com.rc.notify.metrics;

import com.rc.notify.config.NotifyProperties;
import com.rc.notify.dispatcher.EndpointGate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 队列健康度采样器（T3.3）：定时查一次，缓存成 {@link QueueStats}，同时喂给 Gauge 与背压判定。
 *
 * <p><b>为什么不在抓取时查库</b>（这是本类最重要的一个决定）：
 * <ol>
 *   <li>Gauge 是在 scrape 时才求值的。如果取值函数里查库，一次 DB 抖动就会让整个
 *       {@code /actuator/prometheus} 返回 500——监控体系因为最需要它的时刻出问题而丢掉全部指标；</li>
 *   <li>被抓取频率 × 实例数放大：Prometheus 15s 抓一次、20 个实例，就是每分钟 80 次聚合查询，
 *       而这台 DB 同时在跑投递队列；</li>
 *   <li>采样可以顺手把"上一次的值"留给背压：DB 短暂不可用时，背压继续按旧快照判断，
 *       而不是把异常当信号。</li>
 * </ol>
 *
 * <p><b>计数封顶</b>：backlog 的 SQL 写成 {@code SELECT COUNT(*) FROM (... LIMIT cap)}。
 * 监控与背压只需要知道"有没有越过阈值"，不需要 50 万这个精确数字；而封顶让这条 index-only scan
 * 在积压 500 万时仍然是扫描 10 万行就停——这是这张表能同时当队列的关键。
 * 封顶取 {@code maxPending + 1}，保证阈值判定永远有效（封顶值低于阈值就是一个永远不触发的背压）。
 */
@Component
public class QueueSampler implements QueueStatsSource {

    private static final Logger log = LoggerFactory.getLogger(QueueSampler.class);

    /** 死信待办量的封顶：没有阈值依赖它，取一个足够看出"量级"的上限。 */
    private static final int DEAD_CAP = 10_001;

    /** 待投递积压条数（封顶）。走 idx_due(status, next_attempt_at) 的 index-only scan。 */
    private static final String BACKLOG_SQL = """
            SELECT COUNT(*) FROM (
              SELECT 1 FROM notification
              WHERE status IN ('PENDING', 'RETRY_WAIT')
              LIMIT ?
            ) t
            """;

    /** 死信待办量（封顶），走 idx_caller_status 之外的 status 前缀或全索引扫描，量级够用。 */
    private static final String DEAD_SQL = """
            SELECT COUNT(*) FROM (
              SELECT 1 FROM notification WHERE status = 'DEAD' LIMIT ?
            ) t
            """;

    /** 在途数：正常 ≤ worker 池大小，行数少，不需要封顶。 */
    private static final String PROCESSING_SQL = "SELECT COUNT(*) FROM notification WHERE status = 'PROCESSING'";

    /**
     * 最老到期任务的逾期秒数。
     *
     * <p>两个状态各取"索引里的第一条"（{@code ORDER BY next_attempt_at LIMIT 1} 走 idx_due 的第一项），
     * 再取两者的最小值——这样无论积压多少，代价都是两次索引定位，而不是 MIN() 聚合。
     *
     * <p>条件里的 {@code next_attempt_at <= NOW(3)} 不能省：没有它，处于退避中的任务（未来时刻）
     * 会被算成"最老"，一个正常的长退避就会把背压触发掉。
     */
    private static final String OLDEST_SQL = """
            SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(next_attempt_at), NOW(3)), 0)
            FROM (
              (SELECT next_attempt_at FROM notification
                WHERE status = 'PENDING' AND next_attempt_at <= NOW(3)
                ORDER BY next_attempt_at LIMIT 1)
              UNION ALL
              (SELECT next_attempt_at FROM notification
                WHERE status = 'RETRY_WAIT' AND next_attempt_at <= NOW(3)
                ORDER BY next_attempt_at LIMIT 1)
            ) t
            """;

    private final JdbcTemplate jdbc;
    private final EndpointGate gate;
    /** backlog 封顶值 = maxPending + 1：必须严格大于阈值，否则背压永不触发。 */
    private final int backlogCap;

    /** 最近一次成功采样的快照；null = 启动以来还没采到过（背压据此 fail-open）。 */
    private final AtomicReference<QueueStats> latest = new AtomicReference<>();

    /** Gauge 的取值源：只读这几个原子量，抓取永远不会触碰数据库。 */
    private final AtomicInteger backlogGauge = new AtomicInteger();
    private final AtomicInteger processingGauge = new AtomicInteger();
    private final AtomicInteger deadGauge = new AtomicInteger();

    /** 连续采样失败次数：日志节流用（DB 抖动时不该每 10s 刷一条 WARN）。 */
    private final AtomicLong failures = new AtomicLong();

    public QueueSampler(JdbcTemplate jdbc, NotifyProperties props, MeterRegistry registry, EndpointGate gate) {
        this.jdbc = jdbc;
        this.gate = gate;
        this.backlogCap = Math.max(1_000, props.backpressure().maxPending() + 1);

        Gauge.builder("notify.queue.backlog", backlogGauge, AtomicInteger::get)
                .description("待投递积压（PENDING+RETRY_WAIT，封顶 maxPending+1）").register(registry);
        Gauge.builder("notify.queue.processing", processingGauge, AtomicInteger::get)
                .description("在途投递数（正常 ≤ worker 池大小）").register(registry);
        Gauge.builder("notify.queue.dead", deadGauge, AtomicInteger::get)
                .description("死信待办量（拉取式消费尚未处理的部分）").register(registry);
        Gauge.builder("notify.queue.oldest.overdue.seconds", this, QueueSampler::oldestOverdueSeconds)
                .description("最老到期任务的逾期秒数；队列健康度的主指标").register(registry);
        Gauge.builder("notify.circuit.open.endpoints", gate, EndpointGate::openCount)
                .description("当前熔断打开的端点数").register(registry);
    }

    /**
     * 采样一次。默认 10s；集成测试可直接调用，不依赖调度器。
     *
     * <p>失败时<b>保留上一次的快照</b>并只在日志里留痕：宁可让背压按 20s 前的数据判断，
     * 也不要因为一次查询异常就把"队列状态未知"当成"一切正常"或"一切异常"。
     */
    @Scheduled(fixedDelayString = "${notify.metrics.sample-interval-ms:10000}")
    public void sample() {
        try {
            int backlog = jdbc.queryForObject(BACKLOG_SQL, Integer.class, backlogCap);
            int processing = jdbc.queryForObject(PROCESSING_SQL, Integer.class);
            int dead = jdbc.queryForObject(DEAD_SQL, Integer.class, DEAD_CAP);
            long overdue = jdbc.queryForObject(OLDEST_SQL, Long.class);

            backlogGauge.set(backlog);
            processingGauge.set(processing);
            deadGauge.set(dead);
            latest.set(new QueueStats(backlog, processing, dead, overdue, Instant.now()));
            failures.set(0);
        } catch (Exception e) {
            long n = failures.incrementAndGet();
            // 第一次 + 之后每 60 次（约 10 分钟）一条：连续失败本身是指标，不该靠刷日志表达
            if (n == 1 || n % 60 == 0) {
                log.warn("队列健康度采样失败（连续 {} 次），背压沿用上次快照: {}", n, e.toString());
            }
        }
    }

    @Override
    public QueueStats current() {
        return latest.get();
    }

    private long oldestOverdueSeconds() {
        QueueStats s = latest.get();
        return s == null ? 0 : s.oldestOverdueSeconds();
    }
}
