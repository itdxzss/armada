package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AndroidGroupOperationErrorMapperTest {

    private final AndroidGroupOperationErrorMapper mapper =
            new AndroidGroupOperationErrorMapper();

    @Test
    void classifiesGroupCreationRateLimitAsAccountRestriction() {
        ProtocolException exception = mapper.toGroupCreateException(
                response("rate-overlimit", null, "429"), account(), "item:11");

        assertThat(exception.errorCode())
                .isEqualTo(ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED);
        assertContext(exception, "group.create", "item:11");
    }

    @Test
    void classifiesOfflineTimeoutValidationAndNonCreateRateLimit() {
        assertThat(mapper.toException(
                response("账号不存在或已下线", null, null),
                account(), "contact.save", "item:11").errorCode())
                .isEqualTo(ProtocolErrorCode.ACCOUNT_NOT_ONLINE);
        assertThat(mapper.toException(
                response("request timeout", null, null),
                account(), "group.members.list", "item:11").errorCode())
                .isEqualTo(ProtocolErrorCode.TIMEOUT);
        assertThat(mapper.toException(
                response("validation", "subject required", null),
                account(), "group.create", "item:11").errorCode())
                .isEqualTo(ProtocolErrorCode.BAD_REQUEST);
        assertThat(mapper.toException(
                response("rate-overlimit", null, "429"),
                account(), "contact.save", "item:11").errorCode())
                .isEqualTo(ProtocolErrorCode.ACCOUNT_BUSY);
    }

    @Test
    void preservesSafeMetadataAndCanonicalContext() {
        ProtocolException exception = mapper.toException(
                response("sensitive native response", null, "999"),
                account(), "group.members.list", "item:11");

        assertThat(exception.errorCode()).isEqualTo(ProtocolErrorCode.UNKNOWN);
        assertThat(exception.httpStatus()).isEqualTo(200);
        assertThat(exception.protocolCode()).contains("999");
        assertThat(exception.getMessage()).doesNotContain("sensitive native response");
        assertContext(exception, "group.members.list", "item:11");
    }

    private static void assertContext(
            ProtocolException exception,
            String operation,
            String operationId) {
        assertThat(exception.backend()).contains(ProtocolBackend.ANDROID);
        assertThat(exception.operation()).contains(operation);
        assertThat(exception.operationId()).contains(operationId);
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
