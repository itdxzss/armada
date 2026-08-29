package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.service.HyperlinkRecipientStateMachine;
import org.junit.jupiter.api.Test;

/** recipient 唯一事实的 ACK 单调推进规则测试。 */
class HyperlinkRecipientStateMachineTest {

    private final HyperlinkRecipientStateMachine stateMachine = new HyperlinkRecipientStateMachine();

    @Test
    void outOfOrderAckOnlyMovesForward() {
        assertThat(stateMachine.advance(
                HyperlinkRecipientStatus.SENDING,
                HyperlinkRecipientStatus.READ))
                .isEqualTo(HyperlinkRecipientStatus.READ);
        assertThat(stateMachine.advance(
                HyperlinkRecipientStatus.READ,
                HyperlinkRecipientStatus.DELIVERED))
                .isEqualTo(HyperlinkRecipientStatus.READ);
        assertThat(stateMachine.advance(
                HyperlinkRecipientStatus.READ,
                HyperlinkRecipientStatus.READ))
                .isEqualTo(HyperlinkRecipientStatus.READ);
    }

    @Test
    void finalFailureCannotBeRevivedByLateAck() {
        assertThat(stateMachine.advance(
                HyperlinkRecipientStatus.FAILED,
                HyperlinkRecipientStatus.READ))
                .isEqualTo(HyperlinkRecipientStatus.FAILED);
        assertThat(stateMachine.advance(
                HyperlinkRecipientStatus.UNREGISTERED,
                HyperlinkRecipientStatus.DELIVERED))
                .isEqualTo(HyperlinkRecipientStatus.UNREGISTERED);
    }

    @Test
    void lateFailureCannotRegressAnAcknowledgedRecipient() {
        assertThat(stateMachine.advance(
                HyperlinkRecipientStatus.READ,
                HyperlinkRecipientStatus.FAILED))
                .isEqualTo(HyperlinkRecipientStatus.READ);
    }
}
