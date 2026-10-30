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
import java.util.concurrent.atomic.AtomicInteger;

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

    private final NotifyProperties props;
    private final ClaimRepository claims;
    private final HttpDeliverer deliverer;
    private final PayloadCodec codec;
    private final ThreadPoolExecutor workers;
    private final String instanceId;

    /** 投递线程池：一个投递 = 一个线程，"空闲 worker 数"由 JVM 免费维护（参数定值依据 §3.5.1）。 */
    public Dispatcher(NotifyProperties props, ClaimRepository claims, HttpDeliverer deliverer, PayloadCodec codec) {
        this.props = props;
        this.claims = claims;
        this.deliverer = deliverer;
        this.codec = codec;
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
            outcome = new HttpDeliverer.Outcome(null, "IO", 0, null);
        }

        try {
            // 明细无条件落库：这次 HTTP 真实发生过，无论状态守卫成败都要留痕（T1.2 文档 §2.5）
            claims.insertAttempt(c.id(), c.attemptNo(), new Timestamp(startMs),
                    outcome.durationMs(), outcome.statusCode(), outcome.errorClass(), outcome.responseSnippet());

            boolean applied;
            if (outcome.isSuccess()) {
                applied = claims.markSucceeded(c.id(), roundToken, outcome.statusCode());
            } else {
                boolean budgetLeft = c.attemptNo() < c.maxAttempts();
                String nextStatus = budgetLeft ? "RETRY_WAIT" : "DEAD";
                long delayS = budgetLeft ? backoffSeconds(c.attemptNo()) : 0;
                applied = claims.markFailed(c.id(), roundToken, nextStatus, delayS,
                        outcome.statusCode(), outcome.errorClass());
            }
            if (!applied) {
                // 守卫失败：租约已被新 worker 接管，本结果过期。不覆盖新状态，warn 留痕。
                log.warn("任务 {} 结果被守卫丢弃（租约已转移，本次为过期结果）", c.id());
            }
        } catch (Exception e) {
            // 落库失败：行仍处于 PROCESSING，租约过期后会被重新认领——不丢，只是多一次尝试
            log.error("任务 {} 结果落库失败，等待租约过期回收: {}", c.id(), e.toString());
        }
    }

    /** T1.2 最小退避：min(cap, base × 2^(n-1))。无抖动、无分类、无 Retry-After——T2.1 替换。 */
    private long backoffSeconds(int attemptNo) {
        NotifyProperties.Backoff b = props.backoff();
        long exp = b.baseMs() << Math.min(attemptNo - 1, 20);
        long delayMs = Math.min(exp, b.capMs());
        return Math.max(1, delayMs / 1000);
    }

    private int leaseTtlSeconds() {
        return (int) (props.dispatcher().leaseTtlMs() / 1000);
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    /** 供监控/测试观察在途投递数。 */
    public int activeWorkers() {
        return workers.getActiveCount();
    }
}
