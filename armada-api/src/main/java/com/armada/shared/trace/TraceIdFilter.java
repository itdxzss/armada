package com.armada.shared.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 校验 HTTP 入口追踪标识，并在请求线程和响应头中暴露最终值。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIds.normalize(request.getHeader(TraceIds.HTTP_HEADER))
                .orElseGet(TraceIds::newTraceId);
        response.setHeader(TraceIds.HTTP_HEADER, traceId);
        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {
            filterChain.doFilter(request, response);
        }
    }
}
