package com.rc.notify.dispatcher;

import com.rc.notify.config.NotifyProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 调度薄壳：只负责"每 pollIntervalMs 触发一次 pollOnce"。
 * 所有逻辑都在 Dispatcher.pollOnce 里，保证集成测试可以绕开调度器手动驱动。
 *
 * <p>notify.dispatcher.scheduler-enabled=false 时静默跳过（测试环境用，
 * 设计文档 §9.2 用例 7：防止后台线程把测试数据当真实流量投出去）。
 */
@Component
public class DispatcherScheduler {

    private final Dispatcher dispatcher;
    private final boolean enabled;

    public DispatcherScheduler(Dispatcher dispatcher, NotifyProperties props) {
        this.dispatcher = dispatcher;
        this.enabled = props.dispatcher().schedulerEnabled();
    }

    @Scheduled(fixedDelayString = "${notify.dispatcher.poll-interval-ms:500}")
    public void tick() {
        if (enabled) {
            dispatcher.pollOnce();
        }
    }
}
