package com.armada.group.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.enums.AccountGroupMembershipStatus;
import org.junit.jupiter.api.Test;

/** 账号群关系状态契约测试。 */
class AccountGroupMembershipStatusTest {

    @Test
    void onlyInGroupAndUnconfirmedAreSendable() {
        assertThat(AccountGroupMembershipStatus.IN_GROUP.sendable()).isTrue();
        assertThat(AccountGroupMembershipStatus.UNCONFIRMED.sendable()).isTrue();
        assertThat(AccountGroupMembershipStatus.KICKED_OUT.sendable()).isFalse();
        assertThat(AccountGroupMembershipStatus.LEFT.sendable()).isFalse();
        assertThat(AccountGroupMembershipStatus.NOT_IN_GROUP.sendable()).isFalse();
    }

    @Test
    void nullAndUnknownCodesFallBackToUnconfirmedOnRead() {
        assertThat(AccountGroupMembershipStatus.fromCode(null))
                .isEqualTo(AccountGroupMembershipStatus.UNCONFIRMED);
        assertThat(AccountGroupMembershipStatus.fromCode(99))
                .isEqualTo(AccountGroupMembershipStatus.UNCONFIRMED);
    }

    @Test
    void apiValuesAndTextsAreStable() {
        assertThat(AccountGroupMembershipStatus.KICKED_OUT.apiValue()).isEqualTo("KICKED_OUT");
        assertThat(AccountGroupMembershipStatus.KICKED_OUT.text()).isEqualTo("被踢出");
        assertThat(AccountGroupMembershipStatus.LEFT.text()).isEqualTo("已主动退出");
    }
}
