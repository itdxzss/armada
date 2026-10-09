package com.armada.account.takeover;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.account.service.impl.AccountStateEventServiceImpl;
import com.armada.account.state.AccountTakeoverAutoReonlineSideEffect;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.scheduler.PullTaskBothSwitchesOffH2Support;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 两个开关同时关闭时，同库真实账号与建群服务保持既有状态流转及命令行为。 */
class AccountTakeoverBothSwitchesOffH2Test {

    private static final long ACCOUNT_ID = 101L;
    private static final long TASK_ID = 11L;
    private static final long EXECUTION_ID = 111L;
    private static final long NOW = 500_000L;
    private static final long RETRY_DELAY = 5_000L;

    private AccountTakeoverH2Support h;
    private PullTaskBothSwitchesOffH2Support task;
    private AccountStateEventService events;

    @BeforeEach
    void setUp() throws Exception {
        h = new AccountTakeoverH2Support();
        h.properties.setEnabled(false);
        task = new PullTaskBothSwitchesOffH2Support(h);
        assertThat(h.properties.isEnabled()).isFalse();
        assertThat(task.properties.isEnabled()).isFalse();
        var sideEffect = new AccountTakeoverAutoReonlineSideEffect(
                h.online, h.properties, h.transactionManager);
        events = h.transactional(new AccountStateEventServiceImpl(h.accounts, h.states, h.ipProxyService,
                List.of(sideEffect), h.policy), AccountStateEventService.class);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void normalReplacedCreatorRemainsOfflineButRealTaskStillPreparesCreate() {
        h.account(ACCOUNT_ID, 2, 1, 1, null);
        h.account(102L, 2, 1, 1, null);
        task.role(ACCOUNT_ID, PullTaskGroupAccountRole.PROMOTER.code(),
                PullTaskGroupAccountAvailability.AVAILABLE.code(), PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());
        task.role(102L, PullTaskGroupAccountRole.MANAGER.code(),
                PullTaskGroupAccountAvailability.AVAILABLE.code(), PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());
        h.jdbc.update("UPDATE pull_task_group_execution SET group_jid=NULL WHERE id=?", EXECUTION_ID);

        assertThat(events.applyStateChanged(replaced())).isTrue();

        assertThat(accountState()).isEqualTo(6);
        assertThat(loginState()).isEqualTo(2);
        assertThat(new AccountAutoTakeoverDispatcher(h.states, h.online, h.properties).dispatchOnce(NOW)).isZero();
        assertThat(h.online.autoTakeover(ACCOUNT_ID).submitted()).isZero();
        var prepared = task.creates.prepareCreate(task.executions.selectById(EXECUTION_ID), NOW, RETRY_DELAY);
        assertThat(prepared.ready()).isTrue();
        assertThat(prepared.command().account().armadaAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(prepared.completedResult()).isNull();
        var execution = task.executions.selectById(EXECUTION_ID);
        assertThat(execution.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(execution.getReasonCode()).isNull();
        assertNoNewAccountRecovery();
    }

    @Test
    void takingOverAccountWithDesiredOfflineStillReonlinesInsideOriginalTransaction() {
        h.account(ACCOUNT_ID, 7, 1, 2, null);

        h.transactions.executeWithoutResult(status -> {
            assertThat(events.applyStateChanged(replaced())).isTrue();
            assertThat(commandCount()).isOne();
        });

        assertThat(accountState()).isEqualTo(7);
        assertThat(loginState()).isEqualTo(3);
        assertThat(h.jdbc.queryForObject(
                "SELECT desired_login_state FROM account_state WHERE account_id=?", Integer.class, ACCOUNT_ID))
                .isEqualTo(2);
        assertThat(h.jdbc.queryForObject("SELECT payload_json FROM protocol_command_outbox", String.class))
                .contains("\"source\":\"login_replaced_takeover\"")
                .doesNotContain("pullTaskId", "groupExecutionId");
        assertThat(breakerRows()).isZero();
    }

    @Test
    void terminalAccountReplacedEventKeepsOldLifecycleOverwrite() {
        h.account(ACCOUNT_ID, 3, 1, 1, null);

        assertThat(events.applyStateChanged(replaced())).isTrue();

        assertThat(accountState()).isEqualTo(6);
        assertThat(loginState()).isEqualTo(2);
        assertNoNewAccountRecovery();
    }

    @Test
    void reservedCreatorReplacedEventSkipsNewRecoveryWithoutReleasingReservation() {
        h.account(ACCOUNT_ID, 2, 1, 1, null);
        h.reserve(ACCOUNT_ID);

        assertThat(events.applyStateChanged(replaced())).isTrue();
        assertThat(accountState()).isEqualTo(6);
        assertThat(loginState()).isEqualTo(2);
        assertThat(h.online.reonlineReservedCreator(ACCOUNT_ID, TASK_ID, EXECUTION_ID).accepted()).isFalse();

        assertThat(h.jdbc.queryForObject(
                "SELECT lifecycle FROM account_creator_deletion WHERE account_id=?", String.class, ACCOUNT_ID))
                .isEqualTo("RESERVED");
        assertNoNewAccountRecovery();
    }

    private AccountStateChangedEvent replaced() {
        return new AccountStateChangedEvent(1L, ACCOUNT_ID, "account-101", "ONLINE", "LOGIN_REPLACED",
                NOW, "LOGIN_REPLACED", 440, "protocol", "failed-attempt", null);
    }

    private void assertNoNewAccountRecovery() {
        assertThat(breakerRows()).isZero();
        assertThat(commandCount()).isZero();
    }

    private int accountState() {
        return h.jdbc.queryForObject(
                "SELECT account_state FROM account_state WHERE account_id=?", Integer.class, ACCOUNT_ID);
    }

    private int loginState() {
        return h.jdbc.queryForObject(
                "SELECT login_state FROM account_state WHERE account_id=?", Integer.class, ACCOUNT_ID);
    }

    private int breakerRows() {
        return h.jdbc.queryForObject("SELECT COUNT(*) FROM account_takeover_breaker", Integer.class);
    }

    private int commandCount() {
        return h.jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Integer.class);
    }
}
