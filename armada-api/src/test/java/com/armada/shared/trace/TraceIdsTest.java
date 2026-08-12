package com.armada.shared.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdsTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_TRACE_ID = "fedcba9876543210fedcba9876543210";

    @Test
    void acceptsOnlyCanonicalTraceIds() {
        assertThat(TraceIds.normalize(FIXED_TRACE_ID)).contains(FIXED_TRACE_ID);
        assertThat(TraceIds.normalize(FIXED_TRACE_ID.toUpperCase())).isEmpty();
        assertThat(TraceIds.normalize("00000000000000000000000000000000")).isEmpty();
        assertThat(TraceIds.normalize("abc\nforged=true")).isEmpty();
        assertThat(TraceIds.normalize(null)).isEmpty();
    }

    @Test
    void generatesCanonicalNonZeroTraceIds() {
        String generated = TraceIds.newTraceId();

        assertThat(generated).hasSize(32);
        assertThat(TraceIds.isValid(generated)).isTrue();
    }

    @Test
    void derivesTheSameTraceFromTheSameStableSeed() {
        String first = TraceIds.stableFrom("command-42");
        String second = TraceIds.stableFrom("command-42");

        assertThat(first).isEqualTo(second);
        assertThat(TraceIds.isValid(first)).isTrue();
        assertThat(TraceIds.stableFrom("command-43")).isNotEqualTo(first);
    }

    @Test
    void canonicalEnvelopeWinsAndReportsHeaderMismatch() {
        TraceIds.Resolution result = TraceIds.resolveCandidates(
                FIXED_TRACE_ID,
                OTHER_TRACE_ID,
                "command-42");

        assertThat(result.traceId()).isEqualTo(FIXED_TRACE_ID);
        assertThat(result.mismatch()).isTrue();
        assertThat(result.source()).isEqualTo(TraceIds.Source.ENVELOPE);
    }

    @Test
    void fallsBackFromEnvelopeToHeaderThenStableSeed() {
        TraceIds.Resolution header = TraceIds.resolveCandidates("invalid", FIXED_TRACE_ID, "command-42");
        TraceIds.Resolution stable = TraceIds.resolveCandidates(null, null, "command-42");

        assertThat(header.traceId()).isEqualTo(FIXED_TRACE_ID);
        assertThat(header.source()).isEqualTo(TraceIds.Source.HEADER);
        assertThat(header.mismatch()).isFalse();
        assertThat(stable.traceId()).isEqualTo(TraceIds.stableFrom("command-42"));
        assertThat(stable.source()).isEqualTo(TraceIds.Source.STABLE);
    }
}
