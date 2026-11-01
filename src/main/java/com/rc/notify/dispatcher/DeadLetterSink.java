package com.rc.notify.dispatcher;

/**
 * 死信出口（T2.3）：一条通知进入 {@code DEAD} 时被调用。
 *
 * <p>为什么要有这层接口，而不是在 Dispatcher 里直接 {@code log.warn}：
 * <ul>
 *   <li><b>死信是必须被看见的失败</b>，但"看见"的通道会变：今天写日志，M3 接 Micrometer 指标，
 *       以后可能还要推 webhook。把通道抽出来，Dispatcher 只负责"宣布"，不关心谁在听；</li>
 *   <li>实现可以任意增减（日志 + 指标 + 告警并存），Dispatcher 不用改一行——注入的是
 *       {@code List<DeadLetterSink>}；</li>
 *   <li>每个实现的异常都必须自己吞掉（见 {@link LoggingDeadLetterSink} 的说明），
 *       这里不定义异常语义，因为出口失败不该反向影响投递结果。</li>
 * </ul>
 *
 * <p>实现约定（硬要求）：<b>必须快速返回且不抛异常</b>。它跑在投递线程上，
 * 一个阻塞 3 秒的 webhook 会占掉一个 worker，等于给投递链路加了一条隐性超时。
 */
public interface DeadLetterSink {

    /** 收到一条死信。实现方负责脱敏与降级，不得抛异常。 */
    void onDead(DeadLetter letter);
}
