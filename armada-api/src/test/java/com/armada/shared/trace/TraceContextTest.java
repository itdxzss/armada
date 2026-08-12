package com.armada.shared.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_TRACE_ID = "fedcba9876543210fedcba9876543210";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void nestedScopesRestoreThePreviousTraceAndCleanUp() {
        assertThat(TraceContext.current()).isEmpty();

        try (TraceContext.Scope ignored = TraceContext.open(FIXED_TRACE_ID)) {
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
            try (TraceContext.Scope nested = TraceContext.open(OTHER_TRACE_ID)) {
                assertThat(TraceContext.current()).contains(OTHER_TRACE_ID);
            }
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
        }

        assertThat(TraceContext.current()).isEmpty();
    }

    @Test
    void concurrentScopesDoNotLeakAcrossThreads() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<List<String>> first = executor.submit(() -> observeTrace(FIXED_TRACE_ID, ready, release));
            Future<List<String>> second = executor.submit(() -> observeTrace(OTHER_TRACE_ID, ready, release));
            ready.await();
            release.countDown();

            assertThat(first.get()).containsExactly(FIXED_TRACE_ID, "empty");
            assertThat(second.get()).containsExactly(OTHER_TRACE_ID, "empty");
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<String> observeTrace(
            String traceId,
            CountDownLatch ready,
            CountDownLatch release) throws InterruptedException {
        String inside;
        try (TraceContext.Scope ignored = TraceContext.open(traceId)) {
            ready.countDown();
            release.await();
            inside = TraceContext.current().orElseThrow();
        }
        return List.of(inside, TraceContext.current().orElse("empty"));
    }
}
