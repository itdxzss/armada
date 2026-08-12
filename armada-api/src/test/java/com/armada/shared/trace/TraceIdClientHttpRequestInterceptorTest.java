package com.armada.shared.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdClientHttpRequestInterceptorTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void sendsExactlyOneCurrentTraceHeader() throws Exception {
        TraceIdClientHttpRequestInterceptor interceptor = new TraceIdClientHttpRequestInterceptor();
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST,
                URI.create("http://protocol.internal/v1/accounts/acc-1/online"));
        request.getHeaders().put(TraceIds.HTTP_HEADER, List.of("stale-1", "stale-2"));
        AtomicReference<List<String>> observed = new AtomicReference<>();

        try (TraceContext.Scope ignored = TraceContext.open(FIXED_TRACE_ID)) {
            interceptor.intercept(request, "{}".getBytes(StandardCharsets.UTF_8), (innerRequest, body) -> {
                observed.set(innerRequest.getHeaders().get(TraceIds.HTTP_HEADER));
                return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
            });
        }

        assertThat(observed.get()).containsExactly(FIXED_TRACE_ID);
    }

    @Test
    void generatesAValidHeaderWhenNoTraceContextExists() throws Exception {
        TraceIdClientHttpRequestInterceptor interceptor = new TraceIdClientHttpRequestInterceptor();
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET,
                URI.create("http://protocol.internal/healthz"));
        AtomicReference<String> observed = new AtomicReference<>();

        interceptor.intercept(request, new byte[0], (innerRequest, body) -> {
            observed.set(innerRequest.getHeaders().getFirst(TraceIds.HTTP_HEADER));
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        });

        assertThat(observed.get()).matches("[0-9a-f]{32}");
        assertThat(TraceContext.current()).isEmpty();
    }
}
