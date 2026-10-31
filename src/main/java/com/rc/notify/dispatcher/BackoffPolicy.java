package com.rc.notify.dispatcher;

import com.rc.notify.config.NotifyProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 退避调度（T2.1，设计文档 §4.4 / 参数定值依据 §3.6）。
 *
 * <p>公式：{@code delay(n) = min(cap, base × 2^(n-1)) × rand(1-j, 1+j)}（全抖动）。
 * 两条刻意的边界（T2.1 文档 §2.5）：
 * <ul>
 *   <li>抖动乘在 cap <b>之后</b>——尾部最大 1.5×cap。cap 的目的是控制节奏量级（小时级），
 *       不是硬上界；</li>
 *   <li>{@code Retry-After} 优先于公式且<b>不抖动</b>——那是对方的承诺，抖动会破坏承诺语义，
 *       只 clamp 到 {@code [1s, cap]} 防御异常值。</li>
 * </ul>
 *
 * <p>重试 10 次 × 该曲线 ≈ 3.5h ≈ 业务补偿窗口（次数是"窗口÷曲线"的解，不是独立 chosen）。
 *
 * <p>无状态、无锁：参数在构造期从配置读死，{@link #delayMs} 只读字段 + 线程本地随机数，
 * 可被 200 个投递线程并发调用。
 */
@Component
public class BackoffPolicy {

    /** 单次退避下限：哪怕配置错了也不能把任务打成 0 间隔风暴。 */
    static final long MIN_DELAY_MS = 1_000;

    /** 2^n 的安全封顶：base=10s 时第 20 次已远超任何合理 cap，防止位移溢出。 */
    private static final int MAX_SHIFT = 20;

    /** 退避基数（ms），默认 10000：第 n 次失败的理论等待 = base×2^(n-1)。 */
    private final long baseMs;

    /** 退避上限（ms），默认 3600000（1h）：指数增长到这里停止，后续保持小时级节奏。 */
    private final long capMs;

    /**
     * 抖动比例，默认 0.5（±50%）：实际等待落在 {@code (1-j, 1+j)} 区间内均匀取值。
     *
     * <p>必须 &gt; 0：{@link ThreadLocalRandom#nextDouble(double)} 的 bound 不能为 0，
     * 该下界由 {@code StartupGuard} 在启动期断言（jitter ∈ (0,1)），本类不再重复校验。
     */
    private final double jitter;

    public BackoffPolicy(NotifyProperties props) {
        NotifyProperties.Backoff b = props.backoff();
        this.baseMs = b.baseMs();
        this.capMs = b.capMs();
        this.jitter = b.jitter();
    }

    /**
     * 第 attemptNo 次失败后的退避毫秒数（下一次投递前等待）。
     *
     * <p>返回值恒 &ge; {@link #MIN_DELAY_MS}；落库时调用方按秒截断
     * （{@code DATE_ADD(NOW(3), INTERVAL ? SECOND)}），不足 1s 的余数丢弃，
     * 所以下限取整秒，避免"算出来 800ms、存进去 0s"变成立即重试。
     *
     * @param attemptNo         本次尝试序号（认领时已自增，&ge; 1）；序号越大等得越久
     * @param retryAfterSeconds 对端 Retry-After 秒数（仅数值格式；null = 走指数退避公式）
     * @return 下次投递前的等待毫秒数
     */
    public long delayMs(int attemptNo, Long retryAfterSeconds) {
        if (retryAfterSeconds != null) {
            // 对方承诺优先：不抖动（抖动会破坏承诺语义），只把异常值 clamp 回 [1s, cap]——
            // 0 会变成风暴，超大值会把任务拖到补偿窗口之外（T2.1 文档 §2.5）
            long promised = retryAfterSeconds * 1000;
            return Math.min(capMs, Math.max(MIN_DELAY_MS, promised));
        }
        // 指数部分：attemptNo-1 次左移 = base×2^(n-1)；先钳到 [0, MAX_SHIFT] 再移位，
        // 既挡住 attemptNo<=0 的负数移位（IllegalArgumentException），也避免次数很大时
        // long 左移把符号位翻掉、算出一个负的等待时间
        long exp = baseMs << Math.min(Math.max(attemptNo - 1, 0), MAX_SHIFT);
        // 先截到 cap 再抖动：cap 约束的是"节奏量级"，不是硬上界，
        // 抖动后的尾部允许到 1.5×cap（1h → 90min）
        long capped = Math.min(exp, capMs);
        // 全抖动：区间 (1-j, 1+j) 内均匀采样；随机源是 ThreadLocalRandom，无锁且按线程隔离
        double factor = 1 - jitter + ThreadLocalRandom.current().nextDouble(2 * jitter);
        // 再兜一次下限：极小的 base 或 cap 被抖动压到 1s 以下时，仍不允许 0 间隔重试
        return Math.max(MIN_DELAY_MS, (long) (capped * factor));
    }
}
