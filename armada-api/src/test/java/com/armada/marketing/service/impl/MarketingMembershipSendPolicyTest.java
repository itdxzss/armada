package com.armada.marketing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.enums.AccountGroupMembershipStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** 营销发送前账号群关系决策测试。 */
class MarketingMembershipSendPolicyTest {

    @ParameterizedTest
    @EnumSource(value = AccountGroupMembershipStatus.class, names = {"IN_GROUP", "UNCONFIRMED"})
    void sendableStatusesContinueToProtocol(AccountGroupMembershipStatus status) {
        assertThat(MarketingMembershipSendPolicy.decide(status).sendable()).isTrue();
    }

    @Test
    void missingStatusKeepsLegacyUnconfirmedEligibility() {
        var decision = MarketingMembershipSendPolicy.decide(null);

        assertThat(decision.sendable()).isTrue();
        assertThat(decision.reasonCode()).isNull();
        assertThat(decision.reasonMessage()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "KICKED_OUT,KICKED_OUT,账号已被踢出群聊",
        "LEFT,LEFT,账号已主动退出群聊",
        "NOT_IN_GROUP,NOT_IN_GROUP,账号当前已不在群聊"
    })
    void exitedStatusesHaveStableSkipReasons(
            AccountGroupMembershipStatus status, String code, String message) {
        var decision = MarketingMembershipSendPolicy.decide(status);
        assertThat(decision.sendable()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(code);
        assertThat(decision.reasonMessage()).isEqualTo(message);
    }
}
