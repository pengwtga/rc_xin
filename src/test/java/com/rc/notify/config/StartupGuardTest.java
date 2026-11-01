package com.rc.notify.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T0.3 验收：故意配错必须拒绝启动（§4.3 约束 1/2）。
 * 单元测试直接驱动静态 validate，不依赖 Spring 上下文与数据库。
 */
class StartupGuardTest {

    /** 与 application.yml 默认值一致的正确配置。 */
    private NotifyProperties valid() {
        return new NotifyProperties(
                new NotifyProperties.Dispatcher(true, 500, 100, 200, 60_000),
                new NotifyProperties.Http(3_000, 15_000),
                new NotifyProperties.Backoff(10_000, 3_600_000, 0.5),
                new NotifyProperties.Retry(10),
                new NotifyProperties.Backpressure(100_000, 30)
        );
    }

    @Test
    void 默认配置应通过自检() {
        assertThatCode(() -> StartupGuard.validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void 租约短于超时加余量应拒绝启动_约束1() {
        var p = valid();
        // lease 60s -> 19s：15s 超时 + 5s 余量 = 20s，19s < 20s，违规
        var broken = new NotifyProperties(
                new NotifyProperties.Dispatcher(true, 500, 100, 200, 19_000),
                p.http(), p.backoff(), p.retry(), p.backpressure());
        assertThatThrownBy(() -> StartupGuard.validate(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-ttl-ms")
                .hasMessageContaining("双投率");
    }

    @Test
    void 批大小超过worker池应拒绝启动_约束2() {
        var p = valid();
        var broken = new NotifyProperties(
                new NotifyProperties.Dispatcher(true, 500, 300, 200, 60_000),
                p.http(), p.backoff(), p.retry(), p.backpressure());
        assertThatThrownBy(() -> StartupGuard.validate(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size-max")
                .hasMessageContaining("worker-pool-size");
    }

    @Test
    void 总超时不大于连接超时应拒绝启动() {
        var p = valid();
        var broken = new NotifyProperties(
                p.dispatcher(), new NotifyProperties.Http(5_000, 3_000),
                p.backoff(), p.retry(), p.backpressure());
        assertThatThrownBy(() -> StartupGuard.validate(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("total-timeout-ms");
    }

    @Test
    void cap小于base应拒绝启动() {
        var p = valid();
        var broken = new NotifyProperties(
                p.dispatcher(), p.http(),
                new NotifyProperties.Backoff(10_000, 5_000, 0.5),
                p.retry(), p.backpressure());
        assertThatThrownBy(() -> StartupGuard.validate(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cap-ms");
    }

    /** 与 application.yml 默认值一致的熔断配置。 */
    private CircuitProperties circuit() {
        return new CircuitProperties(true, 60, 20, 0.5, 60_000, 1, 16, 30, 2, 10_000);
    }

    @Test
    void 默认熔断配置应通过自检() {
        assertThatCode(() -> StartupGuard.validateCircuit(circuit(), valid()))
                .doesNotThrowAnyException();
    }

    @Test
    void 单端点并发超过worker池应拒绝启动_否则隔离静默失效() {
        // worker 池 200、单端点上限 500：池里最多只有 200 个在途，这个上限永远拦不住任何请求
        var broken = new CircuitProperties(true, 60, 20, 0.5, 60_000, 1, 500, 30, 2, 10_000);
        assertThatThrownBy(() -> StartupGuard.validateCircuit(broken, valid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-per-endpoint")
                .hasMessageContaining("永远不会生效");
    }

    @Test
    void 跳过延迟为零应拒绝启动_避免认领退还热循环() {
        var broken = new CircuitProperties(true, 60, 20, 0.5, 60_000, 1, 16, 0, 2, 10_000);
        assertThatThrownBy(() -> StartupGuard.validateCircuit(broken, valid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skip-delay-seconds");
    }
}
