package com.rc.notify.dispatcher;

import com.rc.notify.config.CircuitProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2.5 端点隔离与熔断的状态机（纯内存，不需要数据库）。
 *
 * <p>用可注入的假时钟把 OPEN 窗口"快进"过去，不靠 sleep——熔断涉及 60s 级的时间常量，
 * 真等的话这些用例一个都跑不了。
 */
class EndpointGateTest {

    private static final String EP = "http://127.0.0.1:9103";

    private final AtomicLong now = new AtomicLong(1_000_000);

    /** minSamples=5、阈值 0.5、OPEN 60s、半开 1 个探针、单端点并发 4。 */
    private EndpointGate gate(int maxConcurrent) {
        return new EndpointGate(new CircuitProperties(
                true, 60, 5, 0.5, 60_000, 1, maxConcurrent, 30, 2, 1_000), now::get);
    }

    private void fail(EndpointGate g, int times) {
        for (int i = 0; i < times; i++) {
            g.record(EP, true);
        }
    }

    @Test
    void 端点键是scheme加host加端口_同主机不同端口是两个端点() {
        assertThat(EndpointGate.endpointOf("http://CRM.Example.com:8080/cb"))
                .isEqualTo("http://crm.example.com:8080");
        assertThat(EndpointGate.endpointOf("https://api.example.com/cb"))
                .isEqualTo("https://api.example.com:443");
        // 同一台机器、不同端口是不同下游：按裸 hostname 归并会把两个服务的健康度混在一起
        assertThat(EndpointGate.endpointOf("http://127.0.0.1:9103/a"))
                .isNotEqualTo(EndpointGate.endpointOf("http://127.0.0.1:9104/a"));
        assertThat(EndpointGate.endpointOf("not a url")).isNull();
    }

    @Test
    void 样本不足时不熔断_两次失败是噪声不是故障() {
        EndpointGate g = gate(4);
        fail(g, 2);
        assertThat(g.isOpen(EP)).isFalse();
        assertThat(g.tryEnter(EP)).isNotNull();
    }

    @Test
    void 达到样本且失败率超阈值则打开_期间拒绝进入() {
        EndpointGate g = gate(4);
        fail(g, 3);       // 3/5 = 60% > 50%
        g.record(EP, false);
        g.record(EP, false);

        assertThat(g.isOpen(EP)).isTrue();
        assertThat(g.tryEnter(EP)).isNull();
        assertThat(g.openEvents()).isEqualTo(1);
    }

    @Test
    void 失败率不超阈值不熔断() {
        EndpointGate g = gate(4);
        fail(g, 2);       // 2/5 = 40% < 50%
        g.record(EP, false);
        g.record(EP, false);
        g.record(EP, false);
        assertThat(g.isOpen(EP)).isFalse();
    }

    @Test
    void 窗口滑走的老失败不再计入() {
        EndpointGate g = gate(4);
        fail(g, 4);              // 若永久累计，加上下面的 2 次失败 = 8 样本/6 失败，必然熔断
        now.addAndGet(61_000);   // 整个窗口滑过去，这 4 次失败不再属于"最近 60s"
        g.record(EP, false);
        g.record(EP, false);
        g.record(EP, true);
        g.record(EP, true);      // 窗口内只有 4 个样本 → 不到 minSamples
        assertThat(g.isOpen(EP)).isFalse();
    }

    @Test
    void 熔断到期进入半开_只放一个探针() {
        EndpointGate g = gate(4);
        fail(g, 3);
        g.record(EP, false);
        g.record(EP, false);
        assertThat(g.tryEnter(EP)).isNull();

        now.addAndGet(60_001);   // OPEN 窗口到期
        assertThat(g.isOpen(EP)).isFalse();      // isOpen 只回答"确定 OPEN"，半开不算
        EndpointGate.Permit probe = g.tryEnter(EP);
        assertThat(probe).isNotNull();           // 唯一的探针
        assertThat(g.tryEnter(EP)).isNull();     // 第二个请求必须继续等，否则等于没熔断
        probe.close();
    }

    @Test
    void 半开探测成功则恢复全量() {
        EndpointGate g = gate(4);
        fail(g, 3);
        g.record(EP, false);
        g.record(EP, false);
        now.addAndGet(60_001);

        EndpointGate.Permit probe = g.tryEnter(EP);
        g.record(EP, false);     // 探针成功
        probe.close();

        assertThat(g.isOpen(EP)).isFalse();
        // 恢复后并发上限回到 4：连开 4 个都拿得到
        EndpointGate.Permit p1 = g.tryEnter(EP);
        EndpointGate.Permit p2 = g.tryEnter(EP);
        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();
        p1.close();
        p2.close();
    }

    @Test
    void 半开探测失败则重新打开_且重新计时() {
        EndpointGate g = gate(4);
        fail(g, 3);
        g.record(EP, false);
        g.record(EP, false);
        now.addAndGet(60_001);

        EndpointGate.Permit probe = g.tryEnter(EP);
        g.record(EP, true);      // 探针失败：对方还没好
        probe.close();

        assertThat(g.isOpen(EP)).isTrue();
        assertThat(g.tryEnter(EP)).isNull();
        now.addAndGet(60_001);
        assertThat(g.tryEnter(EP)).isNotNull();   // 重新计时后才再放探针
    }

    @Test
    void 单端点并发上限生效_拿不到就拒绝而不是排队() {
        EndpointGate g = gate(2);
        EndpointGate.Permit a = g.tryEnter(EP);
        EndpointGate.Permit b = g.tryEnter(EP);
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(g.tryEnter(EP)).isNull();     // 第三个被拒：worker 不该在这里阻塞

        a.close();
        EndpointGate.Permit c = g.tryEnter(EP);
        assertThat(c).isNotNull();  // 归还后立刻可用
        b.close();
        c.close();
    }

    @Test
    void 跟踪表到达上限后新端点放行但不隔离_内存必须有界() {
        EndpointGate g = new EndpointGate(new CircuitProperties(
                true, 60, 5, 0.5, 60_000, 1, 4, 30, 2, 1), now::get);
        assertThat(g.tryEnter("http://a.example.com:80")).isNotNull();
        assertThat(g.trackedEndpoints()).isEqualTo(1);

        EndpointGate.Permit overflow = g.tryEnter("http://b.example.com:80");
        assertThat(overflow).isNotNull();          // 放行（不因为内存满而拒绝服务）
        assertThat(g.trackedEndpoints()).isEqualTo(1);  // 但不建记录 → 该端点没有隔离
        overflow.close();
    }

    @Test
    void 关闭开关后不做任何隔离与熔断() {
        EndpointGate g = new EndpointGate(new CircuitProperties(
                false, 60, 5, 0.5, 60_000, 1, 1, 30, 2, 1_000), now::get);
        for (int i = 0; i < 50; i++) {
            g.record(EP, true);
        }
        assertThat(g.isOpen(EP)).isFalse();
        assertThat(g.tryEnter(EP)).isNotNull();
        assertThat(g.tryEnter(EP)).isNotNull();    // 并发上限也不生效
    }

    @Test
    void 跳过延迟恒为正且带抖动() {
        EndpointGate g = gate(4);
        for (int i = 0; i < 50; i++) {
            assertThat(g.skipDelaySeconds(true)).isBetween(24L, 36L);   // 30s ±20%
            assertThat(g.skipDelaySeconds(false)).isBetween(1L, 3L);    // 2s ±20%，下限 1
        }
    }
}
