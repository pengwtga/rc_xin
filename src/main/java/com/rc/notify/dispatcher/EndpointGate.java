package com.rc.notify.dispatcher;

import com.rc.notify.config.CircuitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 端点隔离与熔断（T2.5，设计文档 §4.5）。
 *
 * <p>一个"端点"= {@code scheme://host:port}。粒度选 authority 而不是裸 hostname：
 * 同一台机器上的 8080 和 9090 是两个完全不同的下游，按 hostname 归并会把它们的健康度混在一起
 * （测试里两个端口就是两个端点的直接证据）。
 *
 * <p><b>状态机</b>（每个端点独立）：
 * <pre>
 *   CLOSED ──(窗口内样本≥minSamples 且失败率&gt;阈值)──&gt; OPEN
 *     ^                                               │ 等 openMs
 *     └────────(探测成功)──── HALF_OPEN &lt;────────────┘
 *                              │ 探测失败
 *                              └──────&gt; OPEN（重新计时）
 * </pre>
 *
 * <p><b>为什么必须有 HALF_OPEN</b>：设计文档只写了"OPEN 60s"，但没有 HALF_OPEN 的话，
 * 60s 到期的瞬间所有排队任务会一起放出去。供应商往往还没恢复，于是又一批 200 个失败请求打过去，
 * 熔断退化成"每 60s 打一轮"的定时炸弹。半开只放一个探针，成功才恢复全量。
 *
 * <p><b>为什么失败样本只算 RETRYABLE</b>（由调用方决定，见 {@link Dispatcher}）：
 * 4xx 是我们请求的问题（凭证过期、路径写错），不是端点健康度。若计入，一个配错凭证的 caller
 * 就能把共用该端点的所有 caller 一起"熔断"——把别人的故障变成自己的故障。
 *
 * <p><b>并发安全</b>：每端点一把锁（{@code synchronized} 在 {@link Endpoint} 上），锁粒度 = 端点，
 * 不同端点之间不互斥；信号量本身无锁。200 个 worker 并发打同一个端点时锁持有时间只有几次自增。
 *
 * <p><b>刻意不做全局熔断</b>：状态只在进程内存，多实例各自判断。全局熔断需要共享存储（Redis），
 * 而"每个实例各自保护自己"已经解决了本阶段的问题（不让自己被打满、不烧自己的预算）。
 */
@Component
public class EndpointGate {

    private static final Logger log = LoggerFactory.getLogger(EndpointGate.class);

    /** 滑动窗口的分桶数：6 桶 × 10s = 60s，比固定窗口少了边界处的"双倍流量"误判。 */
    private static final int WINDOW_BUCKETS = 6;

    private final CircuitProperties cfg;
    /** 可注入的时钟：单测用它把 OPEN 窗口"快进"过去，不靠 sleep。 */
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Endpoint> endpoints = new ConcurrentHashMap<>();

    /** 熔断打开次数（M3 接指标：这是"供应商正在故障"的第一信号）。 */
    private final AtomicLong openEvents = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public EndpointGate(CircuitProperties cfg) {
        this(cfg, System::currentTimeMillis);
    }

    /** 供单测注入假时钟（两个构造器时需要显式标 @Autowired，否则 Spring 要求无参构造）。 */
    EndpointGate(CircuitProperties cfg, LongSupplier clock) {
        this.cfg = cfg;
        this.clock = clock;
    }

    /**
     * 端点键：{@code scheme://host:port}，小写，缺省端口补齐（http→80 / https→443）。
     *
     * @return null = URL 解析不出端点（调用方按"不隔离"处理，该任务仍会被投递期 SSRF 校验拦下）
     */
    public static String endpointOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (scheme == null || host == null || host.isEmpty()) {
                return null;
            }
            int port = u.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            }
            return scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 纯读判定：该端点此刻是否确定处于 OPEN。
     *
     * <p>给 {@code pollOnce} 做认领后的前置过滤用——<b>不消耗任何配额、不触发半开</b>，
     * 所以可以在提交线程池之前安全调用。真正的准入（含半开探针与并发上限）在 {@link #tryEnter} 里。
     */
    public boolean isOpen(String endpoint) {
        if (!cfg.enabled() || endpoint == null) {
            return false;
        }
        Endpoint e = endpoints.get(endpoint);
        return e != null && e.isOpen(clock.getAsLong());
    }

    /**
     * 尝试进入一次投递。
     *
     * @return null = 拒绝（熔断中，或该端点的并发已满）——调用方必须<b>退还</b>这条任务而不是当失败处理；
     *         {@link Permit#NOOP} = 放行且不做任何隔离（端点不可解析、或跟踪表已满）
     */
    public Permit tryEnter(String endpoint) {
        if (!cfg.enabled() || endpoint == null) {
            return Permit.NOOP;
        }
        Endpoint e = tracked(endpoint);
        if (e == null) {
            // 内存有界优先于隔离完整：超出跟踪上限的端点退化为"不隔离"。见 T2.5 文档 §6.4
            return Permit.NOOP;
        }
        Semaphore slot = e.enter(clock.getAsLong());
        return slot == null ? null : new Permit(e, slot);
    }

    /**
     * 记录一次真实投递结果（跳过/被拒的不算，它们没有结果）。
     *
     * @param failure true 仅表示"可重试失败"（网络层 + 408/429/5xx）；4xx/3xx/SSRF 拒绝都不算
     */
    public void record(String endpoint, boolean failure) {
        if (!cfg.enabled() || endpoint == null) {
            return;
        }
        Endpoint e = tracked(endpoint);
        if (e != null) {
            e.record(failure, clock.getAsLong());
        }
    }

    /**
     * 取该端点的状态记录，没有就按上限创建。
     *
     * <p>创建动作只在这一个地方发生，{@code tryEnter} 与 {@code record} 共用：
     * 否则"先 record 后 enter"这种调用顺序会静默丢掉样本，而那正是熔断最不该失灵的时刻。
     *
     * @return null = 跟踪表已满，调用方按"不隔离"处理
     */
    private Endpoint tracked(String endpoint) {
        Endpoint e = endpoints.get(endpoint);
        if (e != null) {
            return e;
        }
        if (endpoints.size() >= cfg.maxTrackedEndpoints()) {
            return null;
        }
        return endpoints.computeIfAbsent(endpoint, k -> new Endpoint(cfg, openEvents));
    }

    /** 被跳过时该推迟多久（秒）：熔断中用长延迟，并发满时用短延迟（只是排队，不是故障）。 */
    public long skipDelaySeconds(boolean circuitOpen) {
        int base = circuitOpen ? cfg.skipDelaySeconds() : cfg.permitDelaySeconds();
        // ±20% 抖动：端点恢复瞬间，被跳过的任务不该在同一秒一起涌回来
        double factor = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Math.max(1, Math.round(base * factor));
    }

    public long openEvents() {
        return openEvents.get();
    }

    /** 跟踪中的端点数（运维观察与测试用）。 */
    public int trackedEndpoints() {
        return endpoints.size();
    }

    /** 复位（运维/测试）：把该端点恢复为 CLOSED 并清空窗口。 */
    public void reset(String endpoint) {
        Endpoint e = endpoints.get(endpoint);
        if (e != null) {
            e.reset();
        }
    }

    /** 一次进入的凭证。必须成对关闭（try-with-resources），否则并发配额会泄漏。 */
    public static final class Permit implements AutoCloseable {

        /** 放行但不隔离：端点不可解析，或跟踪表已满。 */
        static final Permit NOOP = new Permit(null, null);

        private final Endpoint endpoint;
        private final Semaphore slot;
        private boolean closed;

        private Permit(Endpoint endpoint, Semaphore slot) {
            this.endpoint = endpoint;
            this.slot = slot;
        }

        @Override
        public void close() {
            if (closed || endpoint == null) {
                closed = true;
                return;
            }
            closed = true;
            endpoint.exit(slot);
        }
    }

    private enum State { CLOSED, OPEN, HALF_OPEN }

    /** 单个端点的状态。所有读写都在本对象锁内。 */
    private static final class Endpoint {

        private final Semaphore permits;
        private final Window window;
        private final CircuitProperties cfg;
        /** 外层的打开计数器引用：静态内部类拿不到外部实例，直接引用传进来。 */
        private final AtomicLong openEvents;

        private State state = State.CLOSED;
        private long openUntilMs;
        /** 半开期间专用配额；每次进入 HALF_OPEN 重建，旧 Permit 释放的是旧对象，天然无害。 */
        private Semaphore probeSlots;

        Endpoint(CircuitProperties cfg, AtomicLong openEvents) {
            this.cfg = cfg;
            this.openEvents = openEvents;
            this.permits = new Semaphore(cfg.maxConcurrentPerEndpoint());
            this.window = new Window(cfg.windowSeconds());
        }

        synchronized boolean isOpen(long now) {
            return state == State.OPEN && now < openUntilMs;
        }

        /** 返回本次占用的信号量；null = 拒绝。 */
        synchronized Semaphore enter(long now) {
            switch (state) {
                case CLOSED:
                    return permits.tryAcquire() ? permits : null;
                case OPEN:
                    if (now < openUntilMs) {
                        return null;
                    }
                    toHalfOpen();
                    return grabProbe();
                case HALF_OPEN:
                default:
                    return grabProbe();
            }
        }

        synchronized void exit(Semaphore slot) {
            slot.release();
        }

        synchronized void record(boolean failure, long now) {
            if (state == State.HALF_OPEN) {
                if (failure) {
                    open(now, "半开探测失败，重新打开");
                } else {
                    state = State.CLOSED;
                    window.clear();
                    probeSlots = null;
                    log.info("端点熔断恢复（半开探测成功），恢复正常投递");
                }
                return;
            }
            if (state == State.OPEN) {
                // OPEN 期间只可能是"打开之前就已在途"的迟到结果，对新状态没有信息量
                return;
            }
            window.add(failure, now);
            int ok = window.successes(now);
            int bad = window.failures(now);
            int samples = ok + bad;
            if (samples >= cfg.minSamples() && (double) bad / samples > cfg.failureRateThreshold()) {
                open(now, "%d 个样本中失败 %d 个（阈值 %.0f%%）"
                        .formatted(samples, bad, cfg.failureRateThreshold() * 100));
            }
        }

        synchronized void reset() {
            state = State.CLOSED;
            window.clear();
            probeSlots = null;
        }

        private Semaphore grabProbe() {
            return probeSlots.tryAcquire() ? probeSlots : null;
        }

        private void toHalfOpen() {
            state = State.HALF_OPEN;
            probeSlots = new Semaphore(cfg.halfOpenProbes());
        }

        private void open(long now, String why) {
            state = State.OPEN;
            openUntilMs = now + cfg.openMs();
            probeSlots = null;
            openEvents.incrementAndGet();
            // 不清窗口：恢复后立刻又要有样本，清空反而让"刚恢复就打满"更晚被发现
            log.warn("端点熔断打开 {}ms：{}；期间该端点的任务只推迟、不消耗尝试预算", cfg.openMs(), why);
        }
    }

    /**
     * 失败率滑动窗口：{@link #WINDOW_BUCKETS} 个桶，每桶 {@code windowSeconds/6} 秒。
     *
     * <p>用分桶而不是两个计数器，是因为固定窗口在边界处会漏：窗口 [0,60) 结束、[60,120) 开始时，
     * 一秒钟内可能涌进两倍流量，21 个样本就凑够 minSamples，噪声被当成故障。
     */
    private static final class Window {

        private final long widthMs;
        private final long[] bucketId;
        private final int[] ok;
        private final int[] bad;

        Window(int windowSeconds) {
            this.widthMs = Math.max(1_000, windowSeconds * 1000L / WINDOW_BUCKETS);
            this.bucketId = new long[WINDOW_BUCKETS];
            this.ok = new int[WINDOW_BUCKETS];
            this.bad = new int[WINDOW_BUCKETS];
            java.util.Arrays.fill(bucketId, Long.MIN_VALUE);
        }

        synchronized void add(boolean failure, long now) {
            long id = now / widthMs;
            int i = (int) Math.floorMod(id, WINDOW_BUCKETS);
            if (bucketId[i] != id) {
                bucketId[i] = id;
                ok[i] = 0;
                bad[i] = 0;
            }
            if (failure) {
                bad[i]++;
            } else {
                ok[i]++;
            }
        }

        synchronized int successes(long now) {
            return sum(now, false);
        }

        synchronized int failures(long now) {
            return sum(now, true);
        }

        private int sum(long now, boolean failure) {
            long newest = now / widthMs;
            int total = 0;
            for (int i = 0; i < WINDOW_BUCKETS; i++) {
                if (bucketId[i] > newest - WINDOW_BUCKETS && bucketId[i] <= newest) {
                    total += failure ? bad[i] : ok[i];
                }
            }
            return total;
        }

        synchronized void clear() {
            java.util.Arrays.fill(bucketId, Long.MIN_VALUE);
            java.util.Arrays.fill(ok, 0);
            java.util.Arrays.fill(bad, 0);
        }
    }
}
