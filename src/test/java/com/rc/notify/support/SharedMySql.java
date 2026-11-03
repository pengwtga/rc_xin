package com.rc.notify.support;

import org.testcontainers.containers.MySQLContainer;

/**
 * 整个测试 JVM 共用<b>一个</b> MySQL 容器（Testcontainers 的 singleton container 模式）。
 *
 * <p>为什么不是每个测试类一个 {@code @Container}：Spring 的上下文缓存会按
 * {@code @SpringBootTest(properties=...)} 分组，本项目有 12 个集成测试类、十来套属性组合，
 * 每套各自起一个 MySQL 就是十几次 10 秒级的容器启动，还会同时占用十几个 795MB 的实例。
 * 改成"进程内单例"之后，容器只在第一次有集成测试触发时启动，之后所有上下文共用它。
 *
 * <p>为什么继承链放在 {@link AbstractIntegrationTest} 而不写进 {@code application.yml}：
 * 用 {@code jdbc:tc:...} URL 虽然一行配置就够，但它是"每个 DataSource 一个容器"，
 * 恰恰放大了上面那个问题；而且把容器生命周期藏在配置串里，读代码时看不见。
 *
 * <p>数据不隔离：所有测试共享同一个库，靠各自的 {@code @BeforeEach} 清表。这与原先是
 * "共享本机 33061 上的 notify 库"完全一致，surefire 默认不并行跑测试类，因此不引入新风险。
 *
 * <p>容器镜像与 {@code docker-compose.yml} 同版本（8.0.46），避免"测试在 8.0.46 过、
 * 生产是 8.0.x 别的补丁版本"这种只在小版本差异上才爆的偏差。镜像需提前 {@code docker pull}
 * （离线环境用 {@code -Dtestcontainers.mysql.image=...} 换成本地已有镜像）。
 */
public final class SharedMySql {

    private static volatile MySQLContainer<?> container;

    private SharedMySql() {
    }

    public static MySQLContainer<?> get() {
        MySQLContainer<?> c = container;
        if (c == null) {
            synchronized (SharedMySql.class) {
                c = container;
                if (c == null) {
                    c = start();
                    container = c;
                }
            }
        }
        return c;
    }

    private static MySQLContainer<?> start() {
        String image = System.getProperty("testcontainers.mysql.image", "mysql:8.0.46");
        // 三个 URL 参数都不能省：useSSL=false 免掉自签证书协商，allowPublicKeyRetrieval
        // 是 caching_sha2_password 在非 TLS 下的必需项，serverTimezone=UTC 让 TIMESTAMP(3)
        // 的读写与生产配置一致（错了会表现为"时间差 8 小时"的假 bug）
        MySQLContainer<?> c = new MySQLContainer<>(image)
                .withDatabaseName("notify")
                .withUsername("notify")
                .withPassword("notify")
                .withUrlParam("useSSL", "false")
                .withUrlParam("allowPublicKeyRetrieval", "true")
                .withUrlParam("serverTimezone", "UTC")
                .withCommand("--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci");
        c.start();
        // 不手动 stop：交给 Testcontainers 的 Ryuk 在 JVM 退出后清理，
        // 免得某个测试类先跑完就把别人还在用的库关掉
        return c;
    }

    public static String jdbcUrl() {
        return get().getJdbcUrl();
    }

    public static String username() {
        return get().getUsername();
    }

    public static String password() {
        return get().getPassword();
    }
}
