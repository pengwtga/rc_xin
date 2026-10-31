package com.rc.notify.dispatcher;

import com.rc.notify.config.NotifyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退避公式边界验证（T2.1 文档 §2.5）：
 * delay(n) = min(cap, base×2^(n-1)) × rand(1-j, 1+j)；Retry-After 优先且不抖动。
 */
class BackoffPolicyTest {

    /** 与 application.yml 一致的默认参数：base=10s、cap=1h、jitter=0.5。 */
    private final BackoffPolicy policy = new BackoffPolicy(new NotifyProperties(
            null, null, new NotifyProperties.Backoff(10_000, 3_600_000, 0.5),
            null, null));

    private static final int SAMPLES = 2_000;

    @Test
    void 首次退避落在半抖动区间() {
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(policy.delayMs(1, null)).isBetween(5_000L, 15_000L);
        }
    }

    @Test
    void 第三次退避按指数增长且带抖动() {
        // 10s × 2^2 = 40s，抖动 [0.5, 1.5] → [20s, 60s]
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(policy.delayMs(3, null)).isBetween(20_000L, 60_000L);
        }
    }

    @Test
    void 尾部被cap钳制后仍可抖到一半倍() {
        // 第 20 次：exp 远超 cap → min=cap=1h，抖动 [0.5,1.5] → [30min, 90min]
        // 抖动乘在 cap 之后是公式原意：cap 控制节奏量级，不是硬上界（T2.1 文档 §2.5）
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(policy.delayMs(20, null)).isBetween(1_800_000L, 5_400_000L);
        }
    }

    @Test
    void RetryAfter优先于公式且不抖动() {
        for (int i = 0; i < SAMPLES; i++) {
            // 对方承诺 120s → 恰好 120s，任何抖动都会破坏承诺语义
            assertThat(policy.delayMs(1, 120L)).isEqualTo(120_000L);
        }
    }

    @Test
    void RetryAfter异常值被clamp到下限() {
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(policy.delayMs(1, 0L)).isEqualTo(1_000L);
            assertThat(policy.delayMs(1, 1L)).isEqualTo(1_000L);
        }
    }

    @Test
    void RetryAfter超大值被clamp到cap() {
        for (int i = 0; i < SAMPLES; i++) {
            assertThat(policy.delayMs(1, 86_400L)).isEqualTo(3_600_000L);
        }
    }
}
