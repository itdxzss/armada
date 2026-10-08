package com.armada.task.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.AccountRoleAvailability;
import org.junit.jupiter.api.Test;

/** 角色等待纯策略的完整边界；不访问数据库或协议服务。 */
class PullTaskOfflineRoleWaitPolicyTest {
    private static final long NOW = 100_000L;

    @Test void missingAccountGivesUp() {
        assertThat(PullTaskOfflineRoleWaitPolicy.decide(null, 30_000L, NOW).kind())
                .isEqualTo(PullTaskOfflineRoleWaitPolicy.Kind.GIVE_UP);
    }

    @Test void onlineAccountIsImmediatelyUsableEvenWithOldOfflineTimestamp() {
        assertThat(decide(AccountRoleAvailability.Kind.ONLINE, 1L).kind())
                .isEqualTo(PullTaskOfflineRoleWaitPolicy.Kind.USE);
    }

    @Test void terminalAccountGivesUpImmediately() {
        assertThat(decide(AccountRoleAvailability.Kind.TERMINAL, NOW).kind())
                .isEqualTo(PullTaskOfflineRoleWaitPolicy.Kind.GIVE_UP);
    }

    @Test void recoveringWithoutOfflineTimestampGivesUp() {
        assertThat(decide(AccountRoleAvailability.Kind.RECOVERING, null).kind())
                .isEqualTo(PullTaskOfflineRoleWaitPolicy.Kind.GIVE_UP);
    }

    @Test void recoveringBeforeDeadlineWaitsUntilOriginalOfflineDeadline() {
        var decision = decide(AccountRoleAvailability.Kind.RECOVERING, NOW - 29_999L);
        assertThat(decision.kind()).isEqualTo(PullTaskOfflineRoleWaitPolicy.Kind.WAIT);
        assertThat(decision.waitUntil()).isEqualTo(NOW + 1);
    }

    @Test void recoveringAtDeadlineGivesUp() {
        assertThat(decide(AccountRoleAvailability.Kind.RECOVERING, NOW - 30_000L).kind())
                .isEqualTo(PullTaskOfflineRoleWaitPolicy.Kind.GIVE_UP);
    }

    @Test void creatorUsesConfiguredGraceInsteadOfReplaceableGrace() {
        var account = new AccountRoleAvailability(1L,
                AccountRoleAvailability.Kind.RECOVERING, NOW - 30_000L, 2, null);
        var decision = PullTaskOfflineRoleWaitPolicy.decide(account, 90_000L, NOW);
        assertThat(decision.kind()).isEqualTo(PullTaskOfflineRoleWaitPolicy.Kind.WAIT);
        assertThat(decision.waitUntil()).isEqualTo(NOW + 60_000L);
    }

    private PullTaskOfflineRoleWaitPolicy.Decision decide(
            AccountRoleAvailability.Kind kind, Long offlineSince) {
        return PullTaskOfflineRoleWaitPolicy.decide(
                new AccountRoleAvailability(1L, kind, offlineSince, 2, null), 30_000L, NOW);
    }
}
