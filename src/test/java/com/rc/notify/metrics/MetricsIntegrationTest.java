package com.rc.notify.metrics;

import com.rc.notify.dispatcher.Dispatcher;
import com.rc.notify.ingest.ApiKeyAuthFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3.3 可观测性集成验收（真实 MySQL + 真实 Tomcat + 本地 HttpServer）。
 *
 * <p>用随机端口而不是 MockMvc：{@code /actuator/prometheus} 是 actuator 自己的
 * {@code WebMvcEndpointHandlerMapping}，MockMvc 切片里不存在——测指标端点必须打真实容器，
 * 否则测的是一段假路径。
 *
 * <p>断言的是"指标名 + 关键 tag 存在"，不是精确数值：Micrometer 注册表在同一个 Spring
 * 上下文里跨测试累积，断言绝对值等于让用例互相依赖。数值正确性由
 * {@link QueueSamplerIntegrationTest} 与 {@link BackpressureGuardTest} 分别钉住。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "notify.dispatcher.scheduler-enabled=false",
        // 投递打的是 127.0.0.1，T2.4 的 SSRF 校验要求显式放行
        "notify.ssrf.allowed-hosts=127.0.0.1",
        // Spring Boot 的测试支持会把 management.defaults.metrics.export.enabled 置为 false
        // （避免测试向外部监控系统导出），于是 Prometheus registry 与 /actuator/prometheus 都不存在。
        // 这里显式打开具体 registry 的导出——测的就是这个端点。
        "management.prometheus.metrics.export.enabled=true",
        "spring.datasource.url=jdbc:mysql://localhost:33061/notify?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
})
@EnabledIfEnvironmentVariable(named = "MYSQL_IT", matches = "true")
class MetricsIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Dispatcher dispatcher;
    @Autowired
    private QueueSampler sampler;

    @LocalServerPort
    private int port;

    private com.sun.net.httpserver.HttpServer mock;
    private int mockPort;

    /**
     * 用 JDK HttpClient 而不是默认的 HttpURLConnection：后者在"服务端返回 401 且响应体流式"时
     * 抛 {@code HttpRetryException: cannot retry due to server authentication}，
     * 而鉴权失败正是本类要断言的一条路径。NoOp 错误处理器让 4xx/5xx 也返回 ResponseEntity，
     * 否则 RestTemplate 会直接抛异常。
     */
    private final RestTemplate rest = new RestTemplate(new JdkClientHttpRequestFactory());

    {
        rest.setErrorHandler(new NoOpResponseErrorHandler());
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM delivery_attempt");
        jdbc.update("DELETE FROM notification");

        mock = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] resp = "{\"received\":true}".getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        mock.start();
        mockPort = mock.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        mock.stop(0);
    }

    @Test
    void 受理加投递后_核心指标都能被Prometheus抓到() throws Exception {
        long id = submit("metrics:1");

        dispatcher.pollOnce();
        awaitNotPending(id);
        sampler.sample();

        String text = scrape();

        // ① 接入 QPS 与错误率：按状态码分（消费方用 rate() 算）
        assertThat(text).containsPattern("notify_ingest_requests_total\\{[^}]*route=\"submit\"[^}]*status=\"202\"");
        // ② 队列深度
        assertThat(text).contains("notify_queue_backlog");
        // ③ 最老待投任务年龄（队列健康度的主指标）
        assertThat(text).contains("notify_queue_oldest_overdue_seconds");
        // ④ 投递成功率：原始计数 + 端点 tag，成功率交给 PromQL 的 rate()
        assertThat(text).containsPattern("notify_delivery_attempts_total\\{[^}]*result=\"success\"");
        assertThat(text).contains("endpoint=\"http://127.0.0.1:" + mockPort + "\"");
        // ⑤ 死信与熔断状态
        assertThat(text).contains("notify_queue_dead");
        assertThat(text).contains("notify_circuit_open_endpoints");
    }

    /** 鉴权失败也要计入受理指标：Filter 在异常处理器之后，看到的才是调用方真正拿到的状态码。 */
    @Test
    void 鉴权失败也计入受理请求指标() {
        assertThat(submitRaw("{}", null)).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(scrape()).containsPattern("notify_ingest_requests_total\\{[^}]*status=\"401\"");
    }

    @Test
    void 幂等命中单独计数_它不是错误但说明有人在重试() {
        String body = """
                {"idempotency_key":"order_paid:metrics_idem","target_url":"https://crm.example.com/cb","body":"{}"}
                """;
        assertThat(submitRaw(body, "k_shop")).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(submitRaw(body, "k_shop")).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(scrape()).containsPattern("notify_ingest_idempotent_hits_total\\{[^}]*caller=\"shop\"");
    }

    // ——— helpers ———

    private String scrape() {
        ResponseEntity<String> response = rest.getForEntity(url("/actuator/prometheus"), String.class);
        assertThat(response.getStatusCode())
                .as("指标端点必须在真实容器里可达；返回体：%s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private long submit(String key) {
        ResponseEntity<String> response = rest.postForEntity(url("/v1/notifications"), entity("""
                {"idempotency_key":"%s","target_url":"http://127.0.0.1:%d/cb","body":"{}"}
                """.formatted(key, mockPort), "k_shop"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return Long.parseLong(response.getBody().replaceAll(".*\"notification_no\":(\\d+).*", "$1"));
    }

    private HttpStatus submitRaw(String json, String apiKey) {
        return HttpStatus.valueOf(rest.postForEntity(url("/v1/notifications"), entity(json, apiKey), String.class)
                .getStatusCode().value());
    }

    private HttpEntity<String> entity(String json, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set(ApiKeyAuthFilter.HEADER_API_KEY, apiKey);
        }
        return new HttpEntity<>(json, headers);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** 等这条通知离开"待投递/在途"：投递是线程池异步的，直接断言会偶发失败。 */
    private void awaitNotPending(long id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String status = jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id);
            if (!"PENDING".equals(status) && !"PROCESSING".equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("通知 " + id + " 未在 5s 内投递完成");
    }
}
