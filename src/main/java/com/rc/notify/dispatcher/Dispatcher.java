package com.rc.notify.dispatcher;

import com.rc.notify.config.NotifyProperties;
import com.rc.notify.metrics.NotifyMetrics;
import com.rc.notify.security.SsrfGuard;
import com.rc.notify.store.ClaimRepository;
import com.rc.notify.store.PayloadCodec;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
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
 *       保证"失败的任务不丢也不卡"（T1.2 文档 §4）；</li>
 *   <li>死信（T2.3）只在状态真正落库成功后宣布，且各出口异常互相隔离——不许"状态没改成 DEAD
 *       却报了死信"，也不许"出口挂了把 DEAD 又变回 PROCESSING"。</li>
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
    /** 出站目标校验（T2.4）：投递前重新解析并校验，是 SSRF 的真正防线。 */
    private final SsrfGuard ssrf;
    /** 端点隔离与熔断（T2.5）：per-endpoint 并发上限 + 失败率熔断。 */
    private final EndpointGate gate;
    /** 指标（T3.3）：投递结果 / 跳过 / 过期结果。名字与 tag 口径全在 NotifyMetrics 里。 */
    private final NotifyMetrics metrics;
    /** 投递线程池，线程数 = workerPoolSize。 */
    private final ThreadPoolExecutor workers;
    /**
     * 死信出口（T2.3）。注入 List：日志、指标、告警 webhook 可以并存，Dispatcher 不关心谁在听。
     * 允许为空（测试或极简部署），宣布动作退化为纯状态落库。
     */
    private final List<DeadLetterSink> deadLetterSinks;
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

    /**
     * 被跳过（熔断中 / 端点并发满）而退还的任务累计数（T2.5）。
     *
     * <p>这个数和 {@link #staleResults} 的区别值得说清：staleResults 是"我们自己的问题"（投递超过了租约），
     * skipped 是"下游的问题"（它在故障或被我们限流）。M3 接指标时两者的告警处置完全不同。
     */
    private final AtomicLong skipped = new AtomicLong();

    /** 投递线程池：一个投递 = 一个线程，"空闲 worker 数"由 JVM 免费维护（参数定值依据 §3.5.1）。 */
    public Dispatcher(NotifyProperties props, ClaimRepository claims, HttpDeliverer deliverer, PayloadCodec codec,
                      ErrorClassifier classifier, BackoffPolicy backoff, List<DeadLetterSink> deadLetterSinks,
                      SsrfGuard ssrf, EndpointGate gate, NotifyMetrics metrics) {
        this.props = props;
        this.claims = claims;
        this.deliverer = deliverer;
        this.codec = codec;
        this.classifier = classifier;
        this.backoff = backoff;
        this.deadLetterSinks = List.copyOf(deadLetterSinks);
        this.ssrf = ssrf;
        this.gate = gate;
        this.metrics = metrics;
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

        // 熔断中的端点不进线程池（T2.5）：认领后立刻整批退还，一条 UPDATE 解决。
        // 放在提交之前而不是 worker 里，是因为"已确定发不出去"的行没必要占一个线程再退出来
        List<Long> deferred = new ArrayList<>();
        for (ClaimRepository.Claimed c : claimed) {
            if (gate.isOpen(EndpointGate.endpointOf(c.targetUrl()))) {
                deferred.add(c.id());
                continue;
            }
            workers.submit(() -> deliverOne(c, roundToken));
        }
        if (!deferred.isEmpty()) {
            long delayS = gate.skipDelaySeconds(true);
            try {
                int released = claims.releaseSkipped(roundToken, deferred, delayS);
                skipped.addAndGet(released);
                metrics.deliverySkipped("circuit_open", released);
                log.debug("{} 条任务因端点熔断被退还（推迟 {}s，不消耗尝试预算）", released, delayS);
            } catch (Exception e) {
                // 退还失败等于这些行停在 PROCESSING：不丢，租约过期后会被重新认领（与落库失败同一兜底）
                log.error("熔断退还失败（{} 条），等租约过期回收: {}", deferred.size(), e.toString());
            }
        }
        return claimed.size();
    }

    /**
     * 投递线程的异常边界：decode / HTTP / 落库任何一步出错都不允许杀死线程或让行卡死。
     *
     * <p>入口先过端点闸门（T2.5）。被拒时<b>退还</b>而不是记失败——这不是"投递失败"，是"这次先不发"。
     * 把拒绝当失败处理，就正好踩中设计文档 §4.5 那个"最容易漏掉的细节"：供应商挂 30 分钟，
     * 排队中的健康任务被我们自己烧成 DEAD。
     */
    private void deliverOne(ClaimRepository.Claimed c, String roundToken) {
        String endpoint = EndpointGate.endpointOf(c.targetUrl());
        EndpointGate.Permit permit = gate.tryEnter(endpoint);
        if (permit == null) {
            boolean circuitOpen = gate.isOpen(endpoint);
            skip(c, roundToken, gate.skipDelaySeconds(circuitOpen), circuitOpen,
                    circuitOpen ? "端点熔断中" : "端点并发已满");
            return;
        }
        try (permit) {
            deliverWithPermit(c, roundToken, endpoint);
        }
    }

    /** 持有端点配额期间的投递与落库（T1.2 起的主体逻辑）。 */
    private void deliverWithPermit(ClaimRepository.Claimed c, String roundToken, String endpoint) {
        long startMs = System.currentTimeMillis();
        HttpDeliverer.Outcome outcome;
        try {
            // 出站目标校验（T2.4）：这是 SSRF 的真正防线——受理时判过的地址，到投递时可能已经变了
            // （DNS rebinding）。放在解密之前：被拒绝的目标连凭证都不该被解出来
            SsrfGuard.Verdict verdict = ssrf.checkForDelivery(c.targetUrl());
            if (verdict.kind() == SsrfGuard.Kind.UNRESOLVED) {
                // DNS 抖动不是"目标非法"，交给重试——下一轮可能就解析出来了
                outcome = new HttpDeliverer.Outcome(null, "DNS", 0, null, null);
            } else if (!verdict.allowed()) {
                // 策略拒绝：请求一个字节都没发出去，分类器会把它判成 PERMANENT（一次即死信）
                log.warn("任务 {} 出站目标被安全策略拒绝，未发出请求：{}", c.id(), verdict.detail());
                outcome = new HttpDeliverer.Outcome(null, ErrorClassifier.SSRF_BLOCKED, 0, null, null);
            } else {
                Map<String, String> headers = codec.decodeHeaders(c.headersEnc());
                String body = codec.decodeBody(c.bodyEnc());
                outcome = deliverer.deliver(new HttpDeliverer.Target(
                        c.httpMethod(), c.targetUrl(), headers, body == null ? null : body.getBytes()));
            }
        } catch (Exception e) {
            log.warn("任务 {} 投递前处理异常，按 IO 失败兜底: {}", c.id(), e.toString());
            outcome = new HttpDeliverer.Outcome(null, "IO", 0, null, null);
        }

        try {
            // 先分类、再落库：error_class 必须与晋级决策出自同一次判断，否则两者会漂移（T2.3 §2.3）
            ErrorClassifier.Decision decision = classifier.classify(outcome);

            // 投递指标（T3.3）：与明细同源——都代表"这次 HTTP 真的发生过"。
            // 跳过与过期结果不在这里，它们没有结果可比
            metrics.deliveryAttempt(endpoint, switch (decision.type()) {
                case SUCCESS -> "success";
                case RETRYABLE -> "retryable";
                case PERMANENT -> "permanent";
            });

            // 端点健康度（T2.5）：只统计"可能自愈的失败"。4xx/3xx/SSRF 拒绝不计入——
            // 那是我们请求的问题，不是端点健康度；计入就等于让一个配错凭证的 caller
            // 把共用该端点的所有 caller 一起熔断掉
            if (decision.type() != ErrorClassifier.Type.PERMANENT) {
                gate.record(endpoint, decision.type() == ErrorClassifier.Type.RETRYABLE);
            }

            // 明细无条件落库：这次 HTTP 真实发生过，无论状态守卫成败都要留痕（T1.2 文档 §2.5）
            claims.insertAttempt(c.id(), c.attemptNo(), new Timestamp(startMs),
                    outcome.durationMs(), outcome.statusCode(), decision.errorClass(), outcome.responseSnippet());

            Transition t = switch (decision.type()) {
                case SUCCESS -> new Transition(claims.markSucceeded(c.id(), roundToken, outcome.statusCode()), null);
                // PERMANENT：一次即 DEAD，不查预算——重试预算只花在可能自愈的失败上（§4.4）
                case PERMANENT -> {
                    // 这里刻意用 debug：死信的 WARN 由 DeadLetterSink 统一发出，
                    // 两处都 WARN 等于同一条死信报两遍（T2.3 文档 §2.5「单一宣布者」）
                    log.debug("任务 {} 判为 PERMANENT（statusCode={}, class={}），转 DEAD",
                            c.id(), outcome.statusCode(), decision.errorClass());
                    yield new Transition(claims.markDead(c.id(), roundToken, outcome.statusCode(), decision.errorClass()),
                            DeadLetter.Reason.PERMANENT);
                }
                case RETRYABLE -> {
                    if (c.attemptNo() < c.maxAttempts()) {
                        // 还有预算：排下一次。落库按秒截断，故 BackoffPolicy 保底 1s（防"算出 800ms 存成 0s"变立即重试）
                        long delayMs = backoff.delayMs(c.attemptNo(), decision.retryAfterSeconds());
                        yield new Transition(claims.markFailed(c.id(), roundToken, delayMs / 1000,
                                outcome.statusCode(), decision.errorClass()), null);
                    }
                    // 预算耗尽：这是"正常花完"，不是异常——第 maxAttempts 次失败在任何重试策略下都会走到这里
                    log.debug("任务 {} 预算耗尽（{}/{} 次，last status={}, class={}），转 DEAD",
                            c.id(), c.attemptNo(), c.maxAttempts(), outcome.statusCode(), decision.errorClass());
                    yield new Transition(claims.markDead(c.id(), roundToken, outcome.statusCode(), decision.errorClass()),
                            DeadLetter.Reason.BUDGET_EXHAUSTED);
                }
            };

            if (!t.applied()) {
                // 守卫失败：租约已被新 worker 接管，本结果过期。不覆盖新状态，warn 留痕。
                // 计入累计值：单次是噪声，持续增长说明该实例的投递耗时已经逼近租约（T2.2）
                metrics.staleResult();
                log.warn("任务 {} 结果被守卫丢弃（租约已转移，本次为过期结果），本实例累计 {} 次",
                        c.id(), staleResults.incrementAndGet());
                return;
            }
            if (t.deadReason() != null) {
                publishDead(c, outcome, decision.errorClass(), t.deadReason());
            }
        } catch (Exception e) {
            // 落库失败：行仍处于 PROCESSING，租约过期后会被重新认领——不丢，只是多一次尝试
            log.error("任务 {} 结果落库失败，等待租约过期回收: {}", c.id(), e.toString());
        }
    }

    /**
     * 退还一条被跳过的任务（T2.5）：退预算、不写明细、稍后再试。
     *
     * <p>与失败落库的区别只在 {@code attempt_count}：都写 {@code RETRY_WAIT}，但这里把它减回去。
     * 所以"到底试过几次"只能看 {@code attempt_count}，不能看状态。
     */
    private void skip(ClaimRepository.Claimed c, String roundToken, long delayS, boolean circuitOpen, String why) {
        try {
            if (claims.releaseSkipped(roundToken, List.of(c.id()), delayS) == 1) {
                skipped.incrementAndGet();
                metrics.deliverySkipped(circuitOpen ? "circuit_open" : "permit_full");
                log.debug("任务 {} 被跳过（{}），推迟 {}s 后再试，不消耗尝试预算", c.id(), why, delayS);
            } else {
                // 守卫失败：租约已转移（本实例被判死过）。与状态落库同一条规矩——不碰别人的行
                log.warn("任务 {} 退还时守卫失败（租约已转移），本次跳过无副作用", c.id());
            }
        } catch (Exception e) {
            log.error("任务 {} 退还失败，等租约过期回收: {}", c.id(), e.toString());
        }
    }

    /**
     * 宣布死信（T2.3）：只在状态<b>真正落库成功</b>后调用。
     *
     * <p>顺序上的这个约束很容易写反：先发事件再落库，一旦守卫失败（租约已被接管），
     * 就会为一个已经被别人接手、可能已经成功的任务发出"死信"——值班的人去查却发现它是 SUCCEEDED。
     *
     * <p>出口异常逐个隔离：一个 webhook 出口挂掉不该让 DEAD 状态变回 PROCESSING，
     * 也不该影响排在它后面的出口。
     */
    private void publishDead(ClaimRepository.Claimed c, HttpDeliverer.Outcome outcome,
                             String errorClass, DeadLetter.Reason reason) {
        if (deadLetterSinks.isEmpty()) {
            return;
        }
        DeadLetter letter = new DeadLetter(c.id(), c.callerId(), c.targetUrl(), c.httpMethod(),
                c.attemptNo(), c.maxAttempts(), outcome.statusCode(), errorClass, reason);
        for (DeadLetterSink sink : deadLetterSinks) {
            try {
                sink.onDead(letter);
            } catch (Exception e) {
                log.warn("死信出口 {} 处理失败（不影响已落库的 DEAD 状态）: {}",
                        sink.getClass().getSimpleName(), e.toString());
            }
        }
    }

    /** 一次状态转换的结果：是否真正落库（守卫成败），以及是否要对外宣布死信。 */
    private record Transition(boolean applied, DeadLetter.Reason deadReason) {
        Transition {
            // 守卫失败说明这行已被他人接管：即使分支判定该进 DEAD，也不许对外宣布（见 publishDead）
            deadReason = applied ? deadReason : null;
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

    /** 本实例因熔断/并发上限而退还的任务累计数（T2.5：下游故障的信号，与我方问题区分）。 */
    public long skipped() {
        return skipped.get();
    }
}
