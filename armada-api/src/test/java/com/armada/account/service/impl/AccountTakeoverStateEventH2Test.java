package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.account.state.AccountTakeoverAutoReonlineSideEffect;
import com.armada.account.takeover.AccountTakeoverH2Support;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 真实账号、熔断及 outbox Mapper 验证被挤事件和提交后独立续上线事务。 */
class AccountTakeoverStateEventH2Test {

    private AccountTakeoverH2Support h;
    private AccountStateEventService events;
    private AccountTakeoverAutoReonlineSideEffect sideEffect;
    private long occurredAt;

    @BeforeEach
    void setUp() throws Exception {
        h = new AccountTakeoverH2Support();
        sideEffect = new AccountTakeoverAutoReonlineSideEffect(h.online, h.properties, h.transactionManager);
        events = h.transactional(new AccountStateEventServiceImpl(h.accounts, h.states, h.ipProxyService,
                List.of(sideEffect), h.policy), AccountStateEventService.class);
        occurredAt = System.currentTimeMillis() + 10_000L;
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void normalAccountBecomesTakingOverOfflineBeforeCommitAndQueuesAfterCommit() {
        h.account(101L, 2, 1, 1, null);

        h.transactions.executeWithoutResult(status -> {
            assertThat(events.applyStateChanged(replaced())).isTrue();
            assertThat(state()).isEqualTo(7);
            assertThat(login()).isEqualTo(2);
            assertThat(outboxCount()).isZero();
        });

        assertThat(state()).isEqualTo(7);
        assertThat(login()).isEqualTo(3);
        assertThat(outboxCount()).isOne();
        assertThat(h.jdbc.queryForObject("SELECT payload_json FROM protocol_command_outbox", String.class))
                .contains("\"source\":\"login_replaced_takeover\"");
    }

    @Test
    void ninthKickContinuesAndTenthKickTripsWithoutEnqueueing() {
        h.account(101L, 2, 1, 1, null);
        for (int index = 0; index < 9; index++) {
            events.applyStateChanged(replaced());
        }
        assertThat(state()).isEqualTo(7);
        assertThat(kicks()).isEqualTo(9);
        assertThat(outboxCount()).isEqualTo(9);

        events.applyStateChanged(replaced());

        assertThat(state()).isEqualTo(6);
        assertThat(login()).isEqualTo(2);
        assertThat(kicks()).isEqualTo(10);
        assertThat(h.jdbc.queryForObject("SELECT tripped_at FROM account_takeover_breaker", Long.class)).isNotNull();
        assertThat(outboxCount()).isEqualTo(9);
    }

    @Test
    void kickAfterConfiguredFixedWindowStartsANewCount() {
        h.account(101L, 2, 1, 1, null);
        events.applyStateChanged(replaced());
        long newWindow = occurredAt + h.properties.getBreakerWindowMs();
        occurredAt = newWindow;

        events.applyStateChanged(replaced());

        assertThat(kicks()).isOne();
        assertThat(h.jdbc.queryForObject("SELECT window_started_at FROM account_takeover_breaker", Long.class))
                .isEqualTo(newWindow);
        assertThat(state()).isEqualTo(7);
    }

    @Test
    void replacedTakingOverAccountWithDesiredOfflineStopsWithoutEnqueueing() {
        h.account(101L, 7, 1, 2, null);

        events.applyStateChanged(replaced());

        assertThat(state()).isEqualTo(6);
        assertThat(login()).isEqualTo(2);
        assertNoRecoveryWork();
    }

    @Test
    void ordinaryOfflineEventAlsoHonorsDesiredOffline() {
        h.account(101L, 7, 1, 2, null);

        events.applyStateChanged(event("OFFLINE", "heartbeat"));

        assertThat(state()).isEqualTo(6);
        assertThat(login()).isEqualTo(2);
        assertNoRecoveryWork();
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 8, 9})
    void replacedTerminalAccountPreservesLifecycleWithoutRecovery(int terminalState) {
        h.account(101L, terminalState, 1, 1, null);

        events.applyStateChanged(replaced());

        assertThat(state()).isEqualTo(terminalState);
        assertThat(login()).isEqualTo(2);
        assertNoRecoveryWork();
    }

    @Test
    void mutedReplacedAccountDoesNotCountOrEnqueue() {
        h.account(101L, 2, 1, 1, 1);

        events.applyStateChanged(replaced());

        assertThat(state()).isEqualTo(6);
        assertThat(login()).isEqualTo(2);
        assertNoRecoveryWork();
    }

    @Test
    void reservedCreatorCountsKickAndCommitsReplacedWithoutGlobalRecovery() {
        h.account(101L, 2, 1, 1, null);
        h.reserve(101L);

        assertThat(events.applyStateChanged(replaced())).isTrue();

        assertThat(state()).isEqualTo(6);
        assertThat(login()).isEqualTo(2);
        assertThat(kicks()).isOne();
        assertThat(outboxCount()).isZero();
        verify(h.ipProxyMock, never()).allocateOnlineEndpoint(any());
    }

    @Test
    void proxyAllocationFailureCannotRollBackCommittedTakingOverState() throws Exception {
        h.account(101L, 2, 1, 1, null);
        when(h.ipProxyMock.allocateOnlineEndpoint(any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION, "测试代理池无空闲代理"));
        TenantContext.set(99L);

        assertThat(events.applyStateChanged(replaced())).isTrue();

        assertThat(TenantContext.get()).isEqualTo(99L);
        try (var connection = h.dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT account_state,login_state FROM account_state WHERE account_id=101")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(7);
            assertThat(rows.getInt(2)).isEqualTo(2);
        }
        assertThat(kicks()).isOne();
        assertThat(outboxCount()).isZero();
        verify(h.ipProxyMock).allocateOnlineEndpoint(any());
    }

    @Test
    void successfulRecoveryUsesEventTenantAndRestoresCallingTenant() {
        h.account(101L, 2, 1, 1, null);
        TenantContext.set(99L);

        events.applyStateChanged(replaced());

        assertThat(TenantContext.get()).isEqualTo(99L);
        assertThat(outboxCount()).isOne();
        assertThat(h.jdbc.queryForObject("SELECT tenant_id FROM protocol_command_outbox", Long.class)).isEqualTo(1L);
    }

    @Test
    void rolledBackStateTransactionNeverStartsRecovery() {
        h.account(101L, 2, 1, 1, null);

        h.transactions.executeWithoutResult(status -> {
            events.applyStateChanged(replaced());
            status.setRollbackOnly();
        });

        assertThat(state()).isEqualTo(2);
        assertThat(login()).isEqualTo(1);
        assertNoRecoveryWork();
    }

    @Test
    void sideEffectWithoutTransactionStartsAndCommitsItsOwnRecoveryTransaction() {
        h.account(101L, 7, 2, 1, null);
        Account account = h.accounts.selectActiveById(101L);
        TenantContext.clear();

        sideEffect.afterStateChanged(account, replaced(), occurredAt);

        assertThat(TenantContext.get()).isNull();
        assertThat(outboxCount()).isOne();
        assertThat(login()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void disabledAccountSwitchPreservesOldReplacedTransition(int initialState) {
        h.properties.setEnabled(false);
        h.account(101L, initialState, 1, 1, null);

        events.applyStateChanged(replaced());

        assertThat(state()).isEqualTo(6);
        assertThat(login()).isEqualTo(2);
        assertNoRecoveryWork();
    }

    @Test
    void disabledAccountSwitchKeepsTakingOverAndEnqueuesWithinStateTransaction() {
        h.properties.setEnabled(false);
        h.account(101L, 7, 1, 2, null);

        h.transactions.executeWithoutResult(status -> {
            events.applyStateChanged(replaced());
            assertThat(outboxCount()).isOne();
        });

        assertThat(state()).isEqualTo(7);
        assertThat(kickRows()).isZero();
    }

    @Test
    void disabledAccountSwitchKeepsOldSynchronousFailureRollback() {
        h.properties.setEnabled(false);
        h.account(101L, 7, 1, 1, null);
        when(h.ipProxyMock.allocateOnlineEndpoint(any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION, "测试代理池无空闲代理"));

        assertThatThrownBy(() -> events.applyStateChanged(replaced())).isInstanceOf(BusinessException.class);

        assertThat(state()).isEqualTo(7);
        assertThat(login()).isEqualTo(1);
        assertThat(kickRows()).isZero();
        assertThat(outboxCount()).isZero();
    }

    private void assertNoRecoveryWork() {
        assertThat(kickRows()).isZero();
        assertThat(outboxCount()).isZero();
        verify(h.ipProxyMock, never()).allocateOnlineEndpoint(any());
    }

    private AccountStateChangedEvent replaced() {
        return event("LOGIN_REPLACED", "protocol");
    }

    private AccountStateChangedEvent event(String to, String source) {
        return new AccountStateChangedEvent(1L, 101L, "account-101", "ONLINE", to,
                occurredAt++, to, "LOGIN_REPLACED".equals(to) ? 440 : null, source, "failed-attempt", null);
    }

    private int state() {
        return h.jdbc.queryForObject("SELECT account_state FROM account_state WHERE account_id=101", Integer.class);
    }

    private int login() {
        return h.jdbc.queryForObject("SELECT login_state FROM account_state WHERE account_id=101", Integer.class);
    }

    private int kicks() {
        return h.jdbc.queryForObject("SELECT kick_count FROM account_takeover_breaker WHERE account_id=101", Integer.class);
    }

    private int kickRows() {
        return h.jdbc.queryForObject("SELECT COUNT(*) FROM account_takeover_breaker", Integer.class);
    }

    private int outboxCount() {
        return h.jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Integer.class);
    }
}
