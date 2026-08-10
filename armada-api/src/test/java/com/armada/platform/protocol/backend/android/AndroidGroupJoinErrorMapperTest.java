package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AndroidGroupJoinErrorMapperTest {

    private final AndroidGroupJoinErrorMapper mapper = new AndroidGroupJoinErrorMapper();

    @Test
    void mapsGinValidationFailureToBadRequest() {
        ProtocolException exception = mapper.toException(
                response("validation failed", "ScanCodeDto.Code required", null),
                account(),
                "group.join",
                "join-task-row:11");

        assertThat(exception.errorCode()).isEqualTo(ProtocolErrorCode.BAD_REQUEST);
    }

    @Test
    void detectsAndMapsExplicitOfflineMessage() {
        AndroidDecodedResponse response = response("账号不存在或已下线", null, null);

        ProtocolException exception = mapper.toException(
                response, account(), "account.status", "account:10");

        assertThat(mapper.isOffline(response)).isTrue();
        assertThat(exception.errorCode()).isEqualTo(ProtocolErrorCode.ACCOUNT_NOT_ONLINE);
    }

    @Test
    void mapsKnownNativeGroupJoinFailures() {
        assertThat(mappedCode("邀请码为空", null)).isEqualTo(ProtocolErrorCode.INVALID_GROUP_LINK);
        assertThat(mappedCode("通过邀请码进群失败, bad-request, Code: 400", "400"))
                .isEqualTo(ProtocolErrorCode.GROUP_UNAVAILABLE);
        assertThat(mappedCode("rate-overlimit", "429")).isEqualTo(ProtocolErrorCode.ACCOUNT_BUSY);
        assertThat(mappedCode("request time out", null)).isEqualTo(ProtocolErrorCode.TIMEOUT);
        assertThat(mappedCode("not-authorized", "403"))
                .isEqualTo(ProtocolErrorCode.GROUP_JOIN_REJECTED);
    }

    @Test
    void preservesRawCodeAndAddsCanonicalContextForUnknownFailure() {
        ProtocolException exception = mapper.toException(
                response("unexpected native failure", null, "999"),
                account(),
                "group.join",
                "join-task-row:11");

        assertThat(exception.errorCode()).isEqualTo(ProtocolErrorCode.UNKNOWN);
        assertThat(exception.httpStatus()).isEqualTo(200);
        assertThat(exception.protocolCode()).contains("999");
        assertThat(exception.backend()).contains(ProtocolBackend.ANDROID);
        assertThat(exception.operation()).contains("group.join");
        assertThat(exception.operationId()).contains("join-task-row:11");
        assertThat(exception.getMessage()).doesNotContain("unexpected native failure");
    }

    private ProtocolErrorCode mappedCode(String message, String rawCode) {
        return mapper.toException(
                response(message, null, rawCode),
                account(),
                "group.join",
                "join-task-row:11")
                .errorCode();
    }

    private static AndroidDecodedResponse response(
            String message,
            String validationError,
            String rawCode) {
        return new AndroidDecodedResponse(
                1003,
                NullNode.getInstance(),
                message,
                validationError,
                rawCode);
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                10L,
                ProtocolBackend.ANDROID,
                "acc_919000000001",
                "919000000001");
    }
}
