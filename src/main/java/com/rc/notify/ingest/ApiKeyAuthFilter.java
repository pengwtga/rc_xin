package com.rc.notify.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 接入鉴权（T1.1）：X-Api-Key → caller，未通过直接 401，不进入 Controller。
 *
 * <p>放在最前是有意的：无效凭证的请求不应消耗任何业务校验与数据库资源（T1.1 文档 §4）。
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** 认证通过后放在 request attribute 里的 caller。 */
    public static final String ATTR_CALLER = "rc.notify.caller";
    public static final String HEADER_API_KEY = "X-Api-Key";

    private final ApiKeyRegistry registry;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(ApiKeyRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /** 只管 /v1/**，actuator 等内部端点不鉴权。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var caller = registry.resolve(request.getHeader(HEADER_API_KEY)).orElse(null);
        if (caller == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "code", "UNAUTHORIZED",
                    "message", "缺少或无效的 " + HEADER_API_KEY));
            return;
        }
        request.setAttribute(ATTR_CALLER, caller);
        chain.doFilter(request, response);
    }
}
