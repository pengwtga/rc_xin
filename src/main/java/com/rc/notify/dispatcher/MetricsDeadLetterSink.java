package com.rc.notify.dispatcher;

import com.rc.notify.metrics.NotifyMetrics;
import org.springframework.stereotype.Component;

/**
 * 死信指标出口（T3.3）：与 {@link LoggingDeadLetterSink} 并列，Dispatcher 不感知。
 *
 * <p>接成 Sink 而不是在 Dispatcher 里直接打点，是为了守住 T2.3 立的那条规矩——
 * <b>死信只有一个宣布者</b>：状态真正落库 DEAD 之后，由 Sink 列表统一宣布。
 * 如果直接在落库分支里计数，守卫失败的那条路径上就会出现"指标说有死信、库里没有"的偏差。
 */
@Component
public class MetricsDeadLetterSink implements DeadLetterSink {

    private final NotifyMetrics metrics;

    public MetricsDeadLetterSink(NotifyMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onDead(DeadLetter letter) {
        metrics.deadLetter(letter.reason().name());
    }
}
