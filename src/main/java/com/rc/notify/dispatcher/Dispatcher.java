package com.rc.notify.dispatcher;

import com.rc.notify.config.NotifyProperties;
import com.rc.notify.store.ClaimRepository;
import com.rc.notify.store.PayloadCodec;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 投递引擎（T1.2）：认领 → 线程池投递 → 状态落库。
 *
 * <p>结构约束（设计文档 V2 §4.3 / T1.2 文档）：
 * <ul>
 *   <li>pollOnce 与 {@code @Scheduled} 分离——集成测试手动驱动，不被后台线程污染（§9.2 用例 7）；</li>
 *   <li>认领是短事务，HTTP 在事务外；batch 先按空闲 worker 钳制再认领（约束 4）；</li>
 *   <li>leaseOwner = 实例ID/轮次token：既做认领回读的定位键，又做状态落库的守卫键（T1.2 文档 §2.2）；</li>
 *   <li>deliverOne 是投递线程的异常边界：任何未预期异常折叠为 IO 失败落 RETRY_WAIT，
 *       保证"失败的任务不丢也不卡"（T1.2 文档 §4）。</li>
 * </ul>
 */
@Component
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    /** 全部运行参数；启动期已由 StartupGuard 断言过约束关系。 */
    private final NotifyProperties props;
    /** 存储侧：认领 / 状态落库 / 明细。 */
    private final ClaimRepository claims;
    /** 出站 HTTP，只回答"这次结果如何"，不做分类。 */
    private final HttpDeliverer deliverer;
    /** Header/body 的加解密，认领后、投递前调用。 */
    private final PayloadCodec codec;
    /** 失败三级分类（T2.1）。 */
    private final ErrorClassifier classifier;
    /** 退避曲线（T2.1）。 */
    private final BackoffPolicy backoff;
    /** 投递线程池，线程数 = workerPoolSize。 */
    private final ThreadPoolExecutor workers;
    /** 本实例标识（12 位随机串），是 leaseOwner 的前半段；重启即变。 */
    private final String instanceId;

    /**
     * 结果被守卫拒绝的累计次数（T2.2）。
     *
     * <p>这是"租约曾被他人接管"的唯一直接信号：正常路径下 token 只属于本轮，守卫不可能失败；
     * 一旦失败，意味着本次投递耗时超过了租约（长 GC）或本实例曾被判定死亡。
     * M3 接指标时直接读这个值。
     */
    private final AtomicLong staleResults = new AtomicLong();

    /** 投递线程池：一个投递 = 一个线程，"空闲 worker 数"由 JVM 免费维护（参数定值依据 §3.5.1）。 */
    public Dispatcher(NotifyProperties props, ClaimRepository claims, HttpDeliverer deliverer, PayloadCodec codec,
                      ErrorClassifier classifier, BackoffPolicy backoff) {
        this.props = props;
        this.claims = claims;
        this.deliverer = deliverer;
        this.codec = codec;
        this.classifier = classifier;
        this.backoff = backoff;
        this.workers = (ThreadPoolExecutor) Executors.newFixedThreadPool(
                props.dispatcher().workerPoolSize(), new java.util.concurrent.ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "dispatch-worker-" + seq.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
        // 实例 ID：12 位随机段，重启即变——崩溃恢复语义里"旧实例复活"本就等于新实例（T1.2 文档 §2.2）
        this.instanceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 认领 + 投递一轮，返回本轮认领的任务数。
     *
     * <p>batch = min(batchSizeMax, 空闲 worker)——认领超过空闲 worker 数的行，
     * 只能等租约过期被重领，是自己制造重复投递（§4.3 约束 4）。
     */
    public int pollOnce() {
        if (workers.isShutdown()) {
            // 停机中：不再认领。认领了却提交不进线程池，行只能等租约过期才被回收（T2.2 §2.6）
            return 0;
        }
        NotifyProperties.Dispatcher d = props.dispatcher();
        int idle = d.workerPoolSize() - workers.getActiveCount();
        int batch = Math.min(d.batchSizeMax(), idle);
        if (batch <= 0) {
            return 0;
        }
        String roundToken = instanceId + "/" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<ClaimRepository.Claimed> claimed = claims.claim(roundToken, batch, leaseTtlSeconds());
        for (ClaimRepository.Claimed c : claimed) {
            workers.submit(() -> deliverOne(c, roundToken));
        }
        return claimed.size();
    }

    /** 投递线程的异常边界：decode / HTTP / 落库任何一步出错都不允许杀死线程或让行卡死。 */
    private void deliverOne(ClaimRepository.Claimed c, String roundToken) {
        long startMs = System.currentTimeMillis();
        HttpDeliverer.Outcome outcome;
        try {
            Map<String, String> headers = codec.decodeHeaders(c.headersEnc());
            String body = codec.decodeBody(c.bodyEnc());
            outcome = deliverer.deliver(new HttpDeliverer.Target(
                    c.httpMethod(), c.targetUrl(), headers, body == null ? null : body.getBytes()));
        } catch (Exception e) {
            log.warn("任务 {} 投递前处理异常，按 IO 失败兜底: {}", c.id(), e.toString());
            outcome = new HttpDeliverer.Outcome(null, "IO", 0, null, null);
        }

        try {
            // 明细无条件落库：这次 HTTP 真实发生过，无论状态守卫成败都要留痕（T1.2 文档 §2.5）
            claims.insertAttempt(c.id(), c.attemptNo(), new Timestamp(startMs),
                    outcome.durationMs(), outcome.statusCode(), outcome.errorClass(), outcome.responseSnippet());

            ErrorClassifier.Decision decision = classifier.classify(outcome);
            boolean applied = switch (decision.type()) {
                case SUCCESS -> claims.markSucceeded(c.id(), roundToken, outcome.statusCode());
                // PERMANENT：一次即 DEAD，不查预算——重试预算只花在可能自愈的失败上（§4.4）
                case PERMANENT -> {
                    log.warn("任务 {} 永久失败（statusCode={}），一次即死信；401/403 需人工介入凭证配置",
                            c.id(), outcome.statusCode());
                    yield claims.markFailed(c.id(), roundToken, "DEAD", 0,
                            outcome.statusCode(), outcome.errorClass());
                }
                case RETRYABLE -> {
                    if (c.attemptNo() < c.maxAttempts()) {
                        long delayMs = backoff.delayMs(c.attemptNo(), decision.retryAfterSeconds());
                        yield claims.markFailed(c.id(), roundToken, "RETRY_WAIT", delayMs / 1000,
                                outcome.statusCode(), outcome.errorClass());
                    } else {
                        yield claims.markFailed(c.id(), roundToken, "DEAD", 0,
                                outcome.statusCode(), outcome.errorClass());
                    }
                }
            };
            if (!applied) {
                // 守卫失败：租约已被新 worker 接管，本结果过期。不覆盖新状态，warn 留痕。
                // 计入累计值：单次是噪声，持续增长说明该实例的投递耗时已经逼近租约（T2.2）
                log.warn("任务 {} 结果被守卫丢弃（租约已转移，本次为过期结果），本实例累计 {} 次",
                        c.id(), staleResults.incrementAndGet());
            }
        } catch (Exception e) {
            // 落库失败：行仍处于 PROCESSING，租约过期后会被重新认领——不丢，只是多一次尝试
            log.error("任务 {} 结果落库失败，等待租约过期回收: {}", c.id(), e.toString());
        }
    }

    private int leaseTtlSeconds() {
        return (int) (props.dispatcher().leaseTtlMs() / 1000);
    }

    /**
     * 停机（T2.2）：先停止收新任务，让在途投递自然结束；最多等 totalTimeout + 1s，超时才强杀。
     *
     * <p>为什么不等到底就直接 shutdownNow：中断在途请求后，那条行的结果永远不会落库，
     * 行停在 PROCESSING，60s 后由别的实例重投一次——**每次滚动发布都制造一批双投**。
     * 等待上限取 totalTimeout+1s 的依据：单次投递有硬超时，超过这个时长仍未结束的线程
     * 只可能是卡在 DB/连接池，再等也不会自己好；而 16s &lt; 租约 60s，等待期内租约仍属于本实例，
     * 不会出现"我还在跑、别人已接手"。
     */
    @PreDestroy
    void shutdown() {
        workers.shutdown();
        try {
            long graceMs = props.http().totalTimeoutMs() + 1_000;
            if (!workers.awaitTermination(graceMs, TimeUnit.MILLISECONDS)) {
                log.warn("停机等待 {}ms 后仍有 {} 个投递未结束，强制中断；这些任务将在租约过期后（<= {}s）被重新认领",
                        graceMs, workers.getActiveCount(), leaseTtlSeconds());
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    /** 供监控/测试观察在途投递数。 */
    public int activeWorkers() {
        return workers.getActiveCount();
    }

    /** 本实例被守卫丢弃的结果累计数（T2.2：崩溃/长 GC 发生过的直接信号）。 */
    public long staleResults() {
        return staleResults.get();
    }
}
