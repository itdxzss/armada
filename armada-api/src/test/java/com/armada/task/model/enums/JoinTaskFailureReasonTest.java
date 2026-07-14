package com.armada.task.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JoinTaskFailureReasonTest {

    @Test
    void labelOfReturnsExplicitLabelForAccountReachoutRestricted() {
        assertThat(JoinTaskFailureReason.labelOf("ACCOUNT_REACHOUT_RESTRICTED"))
                .isEqualTo("账号触达受限，无法进群");
    }

    @Test
    void labelOfReturnsExplicitLabelsForPermanentGroupJoinFailures() {
        assertThat(JoinTaskFailureReason.labelOf("INVITE_INVALID"))
                .isEqualTo("群邀请码无效");
        assertThat(JoinTaskFailureReason.labelOf("INVITE_REVOKED"))
                .isEqualTo("群邀请链接已失效");
        assertThat(JoinTaskFailureReason.labelOf("GROUP_UNAVAILABLE"))
                .isEqualTo("群不可用或已封禁");
    }
}
