package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import com.armada.hyperlink.task.service.HyperlinkSendFailurePolicy;
import org.junit.jupiter.api.Test;

class HyperlinkSendFailurePolicyTest {
    @Test void ack463KeepsRotatingSendersWithCappedDelayWithoutPausing() {
        assertThat(HyperlinkSendFailurePolicy.nextRetryAt(1, "WA_ACK_REJECTED_463", 1000)).isEqualTo(31000);
        assertThat(HyperlinkSendFailurePolicy.nextRetryAt(2, "WA_ACK_REJECTED_463", 1000)).isEqualTo(61000);
        assertThat(HyperlinkSendFailurePolicy.nextRetryAt(3, "WA_ACK_REJECTED_463", 1000)).isEqualTo(121000);
        assertThat(HyperlinkSendFailurePolicy.nextRetryAt(4, "WA_ACK_REJECTED_463", 1000)).isEqualTo(121000);
        assertThat(HyperlinkSendFailurePolicy.nextRetryAt(Integer.MAX_VALUE, "WA_ACK_REJECTED_463", 1000)).isEqualTo(121000);
        assertThat(HyperlinkSendFailurePolicy.unavailableAccount("WA_ACK_REJECTED_463")).isFalse();
    }

    @Test void uncertainNativeErrorsDoNotAuthorizeRetry() {
        assertThat(HyperlinkSendFailurePolicy.definitelyNotSent(null, "SEND_FAILED")).isFalse();
        assertThat(HyperlinkSendFailurePolicy.definitelyNotSent(null, "ACCOUNT_BANNED")).isFalse();
        assertThat(HyperlinkSendFailurePolicy.definitelyNotSent("NOT_SENT", "ACCOUNT_BANNED")).isTrue();
    }
    @Test void sessionErrorsAreRecoverableAndNeverTargetEvidence() {
        for (String code : new String[]{"LID_TARGET_CIPHERTEXT_MISSING", "RECIPIENT_SESSION_UNAVAILABLE"}) {
            assertThat(HyperlinkSendFailurePolicy.targetFailure(code)).isFalse();
            assertThat(HyperlinkSendFailurePolicy.definitelyNotSent("NOT_SENT", code)).isTrue();
            assertThat(HyperlinkSendFailurePolicy.nextRetryAt(1, code, 1000)).isEqualTo(31000);
            assertThat(HyperlinkSendFailurePolicy.nextRetryAt(2, code, 1000)).isEqualTo(61000);
            assertThat(HyperlinkSendFailurePolicy.nextRetryAt(3, code, 1000)).isEqualTo(121000);
            assertThat(HyperlinkSendFailurePolicy.nextRetryAt(4, code, 1000)).isEqualTo(121000);
        }
    }
    @Test void configurationRejectionsBackOffAndUnrelated404IsNotBadTarget() {
        assertThat(HyperlinkSendFailurePolicy.nextRetryAt(1, "UNSUPPORTED_MESSAGE_TYPE", 1000)).isEqualTo(31000);
        assertThat(HyperlinkSendFailurePolicy.targetFailure("HTTP_404")).isFalse();
        assertThat(HyperlinkSendFailurePolicy.targetFailure("RECIPIENT_UNREGISTERED")).isTrue();
        assertThat(HyperlinkSendFailurePolicy.targetFailure("UNREGISTERED")).isTrue();
    }

    @Test void accountFailuresNeverPauseTheWholeTaskAtAnyRetryNumber() {
        for (String code : new String[]{"ACCOUNT_BANNED", "ACCOUNT_OFFLINE", "DEVICE_REMOVED"}) {
            for (int attempt : new int[]{1, 2, 3, 4, 6, 9, 30}) {
                assertThat(HyperlinkSendFailurePolicy.nextRetryAt(attempt, code, 1000))
                        .isBetween(31000L, 121000L);
            }
        }
    }
}
