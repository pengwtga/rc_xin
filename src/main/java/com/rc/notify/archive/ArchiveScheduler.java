package com.rc.notify.archive;

import com.rc.notify.config.ArchiveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 归档调度薄壳（T3.3）：只负责"到点调一次 runOnce"，所有逻辑在 {@link ArchiveService}。
 *
 * <p>与 {@link com.rc.notify.dispatcher.DispatcherScheduler} 同一套路：调度与执行分离，
 * 集成测试绕开调度器手动驱动。
 *
 * <p>{@code notify.archive.enabled=false} 时<b>整个 bean 不注册</b>（而不是"注册但不做事"）：
 * 关掉归档是部署决策（比如由外部 DBA 任务统一管理 PII 留存），不该在进程里留一个空转的定时器。
 */
@Component
@ConditionalOnProperty(name = "notify.archive.enabled", havingValue = "true", matchIfMissing = true)
public class ArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArchiveScheduler.class);

    private final ArchiveService service;
    private final ArchiveProperties cfg;

    public ArchiveScheduler(ArchiveService service, ArchiveProperties cfg) {
        this.service = service;
        this.cfg = cfg;
        log.info("归档任务已启用：每天 {} 清理成功 >{}h / 失败 >{}d 的请求体与响应片段",
                cfg.cron(), cfg.successRetentionHours(), cfg.failureRetentionDays());
    }

    @Scheduled(cron = "${notify.archive.cron:0 30 3 * * *}")
    public void daily() {
        try {
            service.runOnce();
        } catch (Exception e) {
            // 定时任务抛异常会被调度器吞掉（只打日志），这里显式留住上下文：
            // 下一轮明天才来，值班的人需要知道"今天没清"
            log.error("归档任务执行失败，本轮未清理（下一轮为明日同一时刻）: {}", e.toString(), e);
        }
    }
}
