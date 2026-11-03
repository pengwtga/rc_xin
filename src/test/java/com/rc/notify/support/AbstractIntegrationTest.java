package com.rc.notify.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 所有集成测试的公共基座：把数据源指向 {@link SharedMySql} 起的容器。
 *
 * <p>继承来的 {@code @DynamicPropertySource} 优先级高于 {@code @SpringBootTest(properties=...)}
 * 与 {@code application.yml}，所以子类不需要、也不应该再写 {@code spring.datasource.url}——
 * 写死了就回到"必须有本机 MySQL"的老路上，而那正是 T4.1 要消除的。
 *
 * <p>副作用上的一条边界：容器在第一次解析动态属性时启动，意味着<b>跑集成测试需要 Docker</b>。
 * 这是 Testcontainers 的前提，不是本项目的额外要求；纯单测（{@code *Test}）不继承本类，
 * 仍然在无 Docker 环境下可跑。
 */
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    protected static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
        // HikariCP 默认 10 个连接；集成测试里有并发认领场景，连接池太小会把
        // "认领不到"伪装成"连接不够"，给 20 让两类原因区分得开
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 20);
    }
}
