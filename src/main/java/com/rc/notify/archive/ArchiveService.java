package com.rc.notify.archive;

import com.rc.notify.config.ArchiveProperties;
import com.rc.notify.metrics.NotifyMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 归档用例（T3.3，设计文档 §4.10 PII 最小留存）：把过了保留期的请求体与响应片段清掉。
 *
 * <p><b>清的是 PII，不是数据</b>：行、状态、attempt 明细全部保留。这个区别很重要——
 * 如果连行一起删，T3.1 的"这条为什么没到"就答不了，T3.2 的人工重放也无从谈起。
 * 存储成本真的到了要处理的时候，那是设计文档 §8 的 V1（按月归档到冷表），不是这里。
 *
 * <p><b>为什么要分批</b>：这表同时是投递队列。一条 {@code UPDATE ... WHERE updated_at < ...}
 * 一次锁十万行，会把认领 SQL 挡在行锁后面——归档任务本身变成一次投递故障。
 * 每批 {@code batchSize}（默认 1000）行、每批自动提交，锁持有时间是毫秒级。
 *
 * <p><b>为什么是"最多 N 批"而不是"清空为止"</b>：首次上线时可能积压几百万行。
 * 一次跑到底意味着几十分钟的高 IO；而"每次清 10 万，第二天继续"在保留期以天计的语义下
 * 完全没有问题，也让任务的执行时长可预测（有上界才好配超时与告警）。
 */
@Service
public class ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);

    /** 成功单的档位名（指标 tag 与日志共用）。 */
    private static final String TIER_SUCCESS = "success";

    /** 失败终态的档位名。 */
    private static final String TIER_FAILED = "failed";

    private final ArchiveRepository repo;
    private final ArchiveProperties cfg;
    private final NotifyMetrics metrics;

    public ArchiveService(ArchiveRepository repo, ArchiveProperties cfg, NotifyMetrics metrics) {
        this.repo = repo;
        this.cfg = cfg;
        this.metrics = metrics;
    }

    /**
     * 跑一轮归档。刻意<b>不看</b> {@code notify.archive.enabled}：
     * 那是调度策略（{@link ArchiveScheduler}），这里是能力。测试与运维手动触发时不该被开关挡住。
     *
     * @return 本轮清理统计（按档位）
     */
    public Result runOnce() {
        long startMs = System.currentTimeMillis();

        // 成功单：投递已完成，请求体只剩"当天排障"的价值
        int successRows = clearTier(TIER_SUCCESS, "SUCCEEDED", cfg.successRetentionHours());
        // 失败终态：要撑到人工重放（T3.2），重放往往发生在几天之后
        int failedHours = cfg.failureRetentionDays() * 24;
        int failedRows = clearTier(TIER_FAILED, "DEAD", failedHours)
                + clearTier(TIER_FAILED, "CANCELED", failedHours);

        long costMs = System.currentTimeMillis() - startMs;
        if (successRows + failedRows > 0 || costMs > 1_000) {
            log.info("归档完成：成功档 {} 行（>{}h）、失败档 {} 行（>{}h），耗时 {}ms",
                    successRows, cfg.successRetentionHours(), failedRows, failedHours, costMs);
        }
        return new Result(successRows, failedRows, costMs);
    }

    /**
     * 清一个状态档位。
     *
     * <p>循环终止有三个出口，缺一不可：
     * <ol>
     *   <li>取不到行——清完了；</li>
     *   <li>本批不足 {@code batchSize}——已经是最后一批，省一次空查；</li>
     *   <li><b>实际清理 0 行</b>——防死循环。并发清理或数据刚好被重放改状态时，
     *       同一批 id 可能反复被选中；没有这个出口，任务会空转到 maxBatches 才停，
     *       期间一直占着连接。</li>
     * </ol>
     */
    private int clearTier(String tier, String status, int retentionHours) {
        int total = 0;
        for (int i = 0; i < cfg.maxBatchesPerRun(); i++) {
            List<Long> ids = repo.pickBatch(status, retentionHours, cfg.batchSize());
            if (ids.isEmpty()) {
                break;
            }
            int cleared = repo.clearPayload(ids);
            // 明细跟着清：请求体和响应片段是同一份隐私的两端
            repo.clearSnippets(ids);
            total += cleared;
            if (cleared == 0 || ids.size() < cfg.batchSize()) {
                break;
            }
        }
        metrics.archiveCleared(tier, total);
        return total;
    }

    /** 一轮归档的结果（日志与测试断言用）。 */
    public record Result(
            /** 成功档清理行数 */
            int successRows,
            /** 失败档清理行数 */
            int failedRows,
            /** 耗时（ms） */
            long costMs
    ) {}
}
