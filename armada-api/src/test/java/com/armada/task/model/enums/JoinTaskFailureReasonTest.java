package com.armada.task.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JoinTaskFailureReasonTest {

    @Test
    void labelOfReturnsExplicitLabelForAccountReachoutRestricted() {
        assertThat(JoinTaskFailureReason.labelOf("ACCOUNT_REACHOUT_RESTRICTED"))
                .isEqualTo("账号触达受限，无法进群");
    }
}
