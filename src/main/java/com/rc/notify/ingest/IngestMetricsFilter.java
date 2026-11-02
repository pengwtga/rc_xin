package com.rc.notify.ingest;

import com.rc.notify.metrics.NotifyMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 受理接口的响应计数（T3.3）：QPS 与错误率的原始数据。
 *
 * <p><b>为什么在 Filter 里数而不在各处打点</b>：受理路径上的出口太多——Bean Validation 失败、
 * 鉴权失败、背压拒绝、幂等命中、正常受理，散着写必然漏一个（漏的那个正好是事故时最想看的）。
 * Filter 在异常处理器之后执行，看到的是调用方真正拿到的状态码，天然完整。
 *
 * <p>只统计 {@code POST /v1/notifications}：查询与重放是运维动作，混进来会把"业务流量"的
 * QPS 口径污染掉（一次事故里的手工查询能让曲线跳一下）。
 *
 * <p><b>必须排在最外层</b>（{@code HIGHEST_PRECEDENCE}）：鉴权失败时
 * {@link ApiKeyAuthFilter} 直接写响应、不再往下传链，排在它里层的 filter 根本不会被执行——
 * 而 401 恰恰是"错误率"里最该看见的那一类。
 */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class IngestMetricsFilter extends OncePerRequestFilter {

    /** 受理接口的唯一路径。 */
    private static final String INGEST_PATH = "/v1/notifications";

    private final NotifyMetrics metrics;

    public IngestMetricsFilter(NotifyMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            // finally：下游抛异常时容器仍会给一个 500，这次请求也确实发生过
            if ("POST".equals(request.getMethod()) && INGEST_PATH.equals(request.getRequestURI())) {
                metrics.ingestRequest("submit", response.getStatus());
            }
        }
    }
}
