package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.takeover.AccountTakeoverH2Support;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** E28-D：真实账号被挤旧状态与任务旧路径在同一个 H2 库、同一组双关闭配置中同时成立。 */
class PullTaskBothSwitchesOffH2Test {
    private PullTaskBothSwitchesOffH2Support h;

    @BeforeEach void setUp() throws Exception {
        h = new PullTaskBothSwitchesOffH2Support(new AccountTakeoverH2Support());
        assertThat(h.accountSupport.properties.isEnabled()).isFalse();
        assertThat(h.properties.isEnabled()).isFalse();
    }

    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void replacedCreatorBeyondGraceStillPreparesCreateAndWaitsIndefinitelyForProfile() {
        h.offlineAccount(901L,null);
        h.onlineAccount(902L,88L);
        h.role(901,4,1,0);
        h.role(902,1,1,0);
        assertLegacyAccount(901);
        assertThat(h.creates.prepareCreate(h.candidate(),h.NOW,5_000L).ready()).isTrue();
        h.jdbc.update("UPDATE pull_task_group_execution SET create_step=4 WHERE id=111");
        assertThat(h.creates.prepareProfile(h.candidate(),5_000L,h.NOW).completedResult()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(h.candidate().getReasonCode()).isEqualTo("GROUP_CREATOR_UNAVAILABLE");
        assertThat(h.candidate().getExecutionStatus()).isEqualTo(2);
        h.jdbc.update("UPDATE pull_task_group_execution SET lock_owner=?,lock_expires_at=? WHERE id=111",h.OWNER,h.NOW+1_010_000L);
        assertThat(h.creates.prepareProfile(h.candidate(),5_000L,h.NOW+1_000_000L).completedResult()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(h.candidate().getExecutionStatus()).isEqualTo(2);
    }

    @Test void offlinePullerBeyondGraceKeepsItsSlotAndIsNeverExpired() {
        h.offlineAccount(901L,89L);
        long role = h.role(901,2,PullTaskGroupAccountAvailability.OFFLINE.code(),2);
        h.waitFor(PullTaskExecutionStage.PULL_EXECUTION.code(),PullTaskWaitResourceType.PULLER.code());
        assertLegacyAccount(901);
        assertThat(h.recovery.recover(h.candidate(),h.OWNER,h.NOW,30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(h.roles.selectById(role).getAvailabilityStatus()).isEqualTo(PullTaskGroupAccountAvailability.OFFLINE.code());
        assertThat(h.roles.selectById(role).getReleasedAt()).isNull();
        assertThat(h.candidate().getReasonCode()).isEqualTo("ACCOUNT_NOT_ONLINE");
    }

    @Test void removedPullerDoesNotAutomaticallyFillVacancyEvenWithOnlineCandidate() {
        h.offlineAccount(901L,89L);
        long old = h.role(901,2,4,2);
        h.jdbc.update("UPDATE pull_task_group_account SET released_at=5000 WHERE id=?",old);
        h.onlineAccount(902L,89L);
        h.waitFor(PullTaskExecutionStage.PULL_EXECUTION.code(),PullTaskWaitResourceType.PULLER.code());
        assertLegacyAccount(901);
        assertThat(h.lookup.findOnlineEligiblePullersByGroupId(89L)).extracting(ref -> ref.armadaAccountId()).containsExactly(902L);
        assertThat(h.recovery.recover(h.candidate(),h.OWNER,h.NOW,30_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(h.candidate().getStage()).isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(h.candidate().getReasonCode()).isEqualTo("PULLER_UNAVAILABLE");
        assertThat(h.roles.selectByExecutionAndRole(111L,2)).hasSize(1);
    }

    @Test void offlineManagerIsReplacedImmediatelyWithoutGraceWaiting() {
        h.offlineAccount(901L,88L);
        long old = h.role(901,1,PullTaskGroupAccountAvailability.OFFLINE.code(),2);
        h.onlineAccount(902L,88L);
        h.waitFor(PullTaskExecutionStage.MANAGER_JOIN.code(),PullTaskWaitResourceType.MANAGER.code());
        assertLegacyAccount(901);
        assertThat(h.recovery.recover(h.candidate(),h.OWNER,2_001L,30_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(h.roles.selectById(old).getAvailabilityStatus()).isEqualTo(4);
        assertThat(h.roles.selectByExecutionAndRole(111L,1)).extracting(row -> row.getAccountId()).contains(902L);
    }

    @Test void onlineCreatorEventNeverIssuesNewWakeSqlWithBothFlagsOff() {
        h.offlineAccount(901L,null);
        h.role(901,4,1,0);
        assertLegacyAccount(901);
        h.jdbc.update("UPDATE account_state SET login_state=1 WHERE account_id=901");
        h.jdbc.update("UPDATE pull_task_group_execution SET reason_code='GROUP_CREATOR_RECONNECTING',"
                + "execution_status=3,lock_owner=NULL,lock_expires_at=NULL,next_run_at=9000 WHERE id=111");
        h.accountSupport.transactions.executeWithoutResult(status -> h.roleEvents.afterStateChanged(
                h.accountSupport.accounts.selectActiveById(901L),new AccountStateChangedEvent(1L,901L,"account-901", "OFFLINE", "ONLINE",
                        5_000L,"ONLINE",null,"protocol",null,null),5_000L));
        assertThat(h.wakeStatements).hasValue(0);
        assertThat(h.candidate().getNextRunAt()).isEqualTo(9_000L);
        assertThat(h.candidate().getVersion()).isEqualTo(2);
    }

    private void assertLegacyAccount(long id) {
        assertThat(h.accountSupport.states.selectByAccountId(id).getAccountState()).isEqualTo(6);
        assertThat(h.accountSupport.states.selectByAccountId(id).getLoginState()).isEqualTo(2);
        assertThat(h.jdbc.queryForObject("SELECT COUNT(*) FROM account_takeover_breaker",Integer.class)).isZero();
        assertThat(h.jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox",Integer.class)).isZero();
    }
}
