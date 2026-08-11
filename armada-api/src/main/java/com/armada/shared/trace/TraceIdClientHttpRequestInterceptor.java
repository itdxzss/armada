package com.armada.shared.trace;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 向协议层 HTTP 请求注入当前追踪标识。
 */
public final class TraceIdClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    /**
     * 覆盖请求中的追踪头并继续执行 HTTP 调用。
     *
     * @param request 当前 HTTP 请求
     * @param body 请求体字节
     * @param execution 请求执行器
     * @return 协议层响应
     * @throws IOException 请求执行失败时抛出
     */
    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        String traceId = TraceContext.current().orElseGet(TraceIds::newTraceId);
        request.getHeaders().set(TraceIds.HTTP_HEADER, traceId);
        return execution.execute(request, body);
    }
}
