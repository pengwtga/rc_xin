package com.rc.notify.ingest;

import com.rc.notify.api.ApiException;
import com.rc.notify.config.NotifyProperties;
import com.rc.notify.metrics.NotifyMetrics;
import com.rc.notify.metrics.QueueStats;
import com.rc.notify.metrics.QueueStatsSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T3.3 背压判定（设计文档 §4.8）：只测"什么时候拒绝、拒绝成什么样"，不依赖 Spring 与数据库。
 *
 * <p>这组用例的价值主要在两条边界：{@code >=} 与 {@code >} 的选择，以及"没有快照时放行"。
 * 阈值判定写反一个符号，线上表现是"要么永不触发、要么全站 503"，两者都不会被开发环境发现。
 */
class BackpressureGuardTest {

    private static final int MAX_PENDING = 100_000;
    private static final int MAX_AGE_MINUTES = 30;
    private static final long RETRY_AFTER = 30;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotifyMetrics metrics = new NotifyMetrics(registry);

    private BackpressureGuard guard(QueueStats stats) {
        QueueStatsSource source = () -> stats;
        return new BackpressureGuard(source, props(), metrics);
    }

    @Test
    void 队列健康时放行() {
        assertThatCode(() -> guard(stats(10, 0)).ensureCapacity()).doesNotThrowAnyException();
    }

    /**
     * 从未采样成功（刚启动 / 数据库不可用）时放行。
     *
     * <p>这一条最容易被"改得更安全"的人改错：拒绝看起来更保守，但此时提交本身也会失败，
     * 不会静默堆积；而拒绝一切会让一次监控故障升级成全站不可用。
     */
    @Test
    void 没有快照时放行_failOpen() {
        assertThatCode(() -> guard(null).ensureCapacity()).doesNotThrowAnyException();
    }

    @Test
    void 最老到期任务超过阈值返回503并带RetryAfter() {
        assertThatThrownBy(() -> guard(stats(10, MAX_AGE_MINUTES * 60L)).ensureCapacity())
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(api.code()).isEqualTo("QUEUE_OVERLOADED");
                    assertThat(api.retryAfterSeconds()).isEqualTo(RETRY_AFTER);
                });
    }

    /** 年龄用 {@code >=}：30 分钟整就该拒绝——阈值是"可接受的上限"，不是"必须超过它"。 */
    @Test
    void 年龄恰好等于阈值即拒绝() {
        assertThatThrownBy(() -> guard(stats(10, MAX_AGE_MINUTES * 60L)).ensureCapacity())
                .isInstanceOf(ApiException.class);
        assertThatCode(() -> guard(stats(10, MAX_AGE_MINUTES * 60L - 1)).ensureCapacity())
                .doesNotThrowAnyException();
    }

    /** 条数用 {@code >}：恰好 10 万条仍在设计容量内，第 100001 条才该被拒。 */
    @Test
    void 积压恰好等于阈值仍放行_超过才拒绝() {
        assertThatCode(() -> guard(stats(MAX_PENDING, 0)).ensureCapacity()).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard(stats(MAX_PENDING + 1, 0)).ensureCapacity())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("队列积压");
    }

    /** 两个阈值都越界时报"年龄"：它更接近 SLA，也更能解释"为什么慢"。 */
    @Test
    void 两个阈值都越界时按年龄报告() {
        assertThatThrownBy(() -> guard(stats(MAX_PENDING + 1, MAX_AGE_MINUTES * 60L + 5)).ensureCapacity())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("最老到期任务");
    }

    @Test
    void 拒绝会记到指标里并按原因分组() {
        assertThatThrownBy(() -> guard(stats(10, MAX_AGE_MINUTES * 60L)).ensureCapacity())
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard(stats(MAX_PENDING + 1, 0)).ensureCapacity())
                .isInstanceOf(ApiException.class);

        assertThat(registry.counter("notify.ingest.backpressure.rejections", "reason", "oldest_overdue").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("notify.ingest.backpressure.rejections", "reason", "backlog").count())
                .isEqualTo(1.0);
    }

    /** 放行路径不该产生拒绝计数——把"看过一眼"也记成拒绝，指标就没有意义了。 */
    @Test
    void 放行不记拒绝指标() {
        guard(stats(10, 0)).ensureCapacity();
        assertThat(registry.find("notify.ingest.backpressure.rejections").counters()).isEmpty();
    }

    private static QueueStats stats(int backlog, long oldestOverdueSeconds) {
        return new QueueStats(backlog, 0, 0, oldestOverdueSeconds, Instant.now());
    }

    private static NotifyProperties props() {
        return new NotifyProperties(
                new NotifyProperties.Dispatcher(true, 500, 100, 200, 60_000),
                new NotifyProperties.Http(3_000, 15_000),
                new NotifyProperties.Backoff(10_000, 3_600_000, 0.5),
                new NotifyProperties.Retry(10),
                new NotifyProperties.Backpressure(MAX_PENDING, MAX_AGE_MINUTES, RETRY_AFTER));
    }
}
