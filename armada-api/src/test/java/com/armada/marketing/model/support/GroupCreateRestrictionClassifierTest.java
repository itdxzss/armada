package com.armada.marketing.model.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import org.junit.jupiter.api.Test;

class GroupCreateRestrictionClassifierTest {

    @Test
    void restrictedReasonDetectsAccountReachoutRestrictedProtocolException() {
        ProtocolException ex = new ProtocolException(
                ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED,
                "协议层错误 422 ACCOUNT_REACHOUT_RESTRICTED: account_reachout_restricted");

        assertThat(GroupCreateRestrictionClassifier.restrictedReason(ex))
                .contains("account_reachout_restricted");
    }

    @Test
    void restrictedReasonDetectsRateOverlimitProtocolException() {
        ProtocolException ex = new ProtocolException(
                ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED,
                "协议层错误 429 ACCOUNT_REACHOUT_RESTRICTED: rate-overlimit");

        assertThat(GroupCreateRestrictionClassifier.restrictedReason(ex))
                .contains("rate-overlimit");
    }

    @Test
    void restrictedReasonDoesNotMatchAccountBusy() {
        ProtocolException ex = new ProtocolException(
                ProtocolErrorCode.ACCOUNT_BUSY,
                "协议层错误 429 ACCOUNT_BUSY: group operation in progress");

        assertThat(GroupCreateRestrictionClassifier.restrictedReason(ex)).isEmpty();
    }
}
