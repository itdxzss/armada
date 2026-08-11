package com.armada.shared.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesAcceptedRequestTraceInsideChainAndOnResponse() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader(TraceIds.HTTP_HEADER, FIXED_TRACE_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (innerRequest, innerResponse) ->
                observed.set(TraceContext.current().orElseThrow()));

        assertThat(observed).hasValue(FIXED_TRACE_ID);
        assertThat(response.getHeader(TraceIds.HTTP_HEADER)).isEqualTo(FIXED_TRACE_ID);
        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    void replacesInvalidRequestTraceWithoutLeakingIt() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader(TraceIds.HTTP_HEADER, "forged\ntrace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (innerRequest, innerResponse) ->
                observed.set(TraceContext.current().orElseThrow()));

        assertThat(observed.get()).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceIds.HTTP_HEADER)).isEqualTo(observed.get());
        assertThat(response.getHeader(TraceIds.HTTP_HEADER)).doesNotContain("forged");
        assertThat(TraceContext.current()).isEmpty();
    }
}
