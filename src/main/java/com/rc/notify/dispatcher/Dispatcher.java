package com.rc.notify.dispatcher;

import com.rc.notify.config.NotifyProperties;
import org.springframework.stereotype.Component;

/**
 * 投递引擎入口（设计文档 §4.3）。
 *
 * <p>M0 骨架：pollOnce 与调度分离 ——
 * 业务逻辑收在 {@link #pollOnce()}（可被集成测试手动驱动，§9.2 用例 7），
 * {@code @Scheduled} 只是每 pollIntervalMs 调一次它的薄壳。
 */
@Component
public class Dispatcher {

    private final NotifyProperties props;

    public Dispatcher(NotifyProperties props) {
        this.props = props;
    }

    /**
     * 认领 + 投递一轮。返回本轮处理的任务数。
     * TODO(M1/T1.2)：SKIP LOCKED 认领 → HTTP 投递 → 状态落库。
     */
    public int pollOnce() {
        return 0;
    }
}
