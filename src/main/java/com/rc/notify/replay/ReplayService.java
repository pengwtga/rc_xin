package com.rc.notify.replay;

import com.rc.notify.api.ApiException;
import com.rc.notify.config.AuthProperties;
import com.rc.notify.config.NotifyProperties;
import com.rc.notify.ingest.ApiKeyAuthFilter;
import com.rc.notify.replay.dto.ReplayRequest;
import com.rc.notify.replay.dto.ReplayResponse;
import com.rc.notify.store.NotificationReadRepository;
import com.rc.notify.store.ReplayRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 人工重放用例（T3.2，设计文档 §4.6）：只把 DEAD 捞回队列，不替人做"能不能重"的判断。
 *
 * <p>为什么没有自动重放：一条 DEAD 可能是"供应商明确拒收"（重放必失败），也可能是
 * "供应商已处理但响应超时"（重放造成重复业务动作）——系统无法区分这两者，只有人知道
 * 业务上能不能承受重复。所以决策权在人，系统只提供能力并留下记录。
 */
@Service
public class ReplayService {

    private final NotificationReadRepository readRepo;
    private final ReplayRepository replayRepo;
    private final NotifyProperties props;

    public ReplayService(NotificationReadRepository readRepo, ReplayRepository replayRepo,
                         NotifyProperties props) {
        this.readRepo = readRepo;
        this.replayRepo = replayRepo;
        this.props = props;
    }

    /**
     * @throws ApiException 404 单号不存在或不属于当前调用方；409 当前状态不是 DEAD
     */
    public ReplayResponse replay(long id, AuthProperties.Client caller, ReplayRequest req) {
        String operator = resolveOperator(req.operator(), caller.callerId());
        String reason = req.reason().trim();

        // 先读快照（预算档位需要 attempt_count），真正的状态判定交给 UPDATE 的守卫重查——
        // 读与写之间的任何变化（比如并发的另一次重放）都会让守卫失败，不会出现基于过期快照的写入
        NotificationReadRepository.DetailRow row = readRepo.find(id, caller.callerId())
                .orElseThrow(() -> ApiException.notFound("通知不存在或无权访问"));
        if (!"DEAD".equals(row.status())) {
            throw ApiException.conflict(
                    "只有 DEAD 状态可以重放，当前 %s（attempt %d/%d）"
                            .formatted(row.status(), row.attemptCount(), row.maxAttempts()));
        }

        // 预算追加一档：档位取调用方当前的配置（与提交时同一来源），不是历史值——
        // 运营给某个 caller 调大了预算，下一次重放自动按新档给
        int tier = caller.maxAttempts() != null ? caller.maxAttempts() : props.retry().maxAttempts();
        int newMax = row.attemptCount() + tier;

        boolean applied = replayRepo.replayToPending(
                id, caller.callerId(), newMax, operator, reason, row.attemptCount());
        if (!applied) {
            // 读的时候还是 DEAD，写的时候不是了：并发的重放先到了。对第二次点击这就是 409
            throw ApiException.conflict("已被并发重放（或状态已变化），请刷新后重试");
        }
        return new ReplayResponse(id, "PENDING", row.attemptCount(), newMax);
    }

    /** 操作人缺省为调用方标识；统一小写存储，避免 "Alice" 与 "alice" 在审计里变成两个人。 */
    private static String resolveOperator(String operator, String callerId) {
        String raw = operator == null || operator.isBlank() ? callerId : operator.trim();
        return raw.toLowerCase(Locale.ROOT);
    }
}
