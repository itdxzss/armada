package com.armada.platform.protocol.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolExceptionTest {

    @Test
    void preservesProtocolFailureMetadataForOrchestration() {
        ProtocolException.Metadata metadata = ProtocolException.Metadata.of(
                409,
                "NOT_OWNER",
                1_500L,
                "http://worker-a.internal:3000");

        ProtocolException exception = new ProtocolException(
                ProtocolErrorCode.NOT_OWNER,
                metadata,
                "request must be retried on owner worker",
                null);

        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.errorCode()).isEqualTo(ProtocolErrorCode.NOT_OWNER);
        assertThat(exception.httpStatus()).isEqualTo(409);
        assertThat(exception.protocolCode()).contains("NOT_OWNER");
        assertThat(exception.retryAfterMs()).contains(1_500L);
        assertThat(exception.ownerEndpoint()).contains("http://worker-a.internal:3000");
        assertThat(exception.getMessage()).isEqualTo("request must be retried on owner worker");
    }

    @Test
    void defaultsNullCodeAndBlankMessageToUnknownProtocolFailure() {
        RuntimeException cause = new RuntimeException("socket closed");

        ProtocolException exception = new ProtocolException(null, "", cause);

        assertThat(exception.errorCode()).isEqualTo(ProtocolErrorCode.UNKNOWN);
        assertThat(exception.httpStatus()).isZero();
        assertThat(exception.protocolCode()).isEmpty();
        assertThat(exception.retryAfterMs()).isEmpty();
        assertThat(exception.ownerEndpoint()).isEmpty();
        assertThat(exception.getMessage()).isEqualTo("协议层调用失败");
        assertThat(exception.getCause()).isSameAs(cause);
    }
}
