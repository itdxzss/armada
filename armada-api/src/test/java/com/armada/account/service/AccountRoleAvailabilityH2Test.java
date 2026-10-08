package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.model.AccountRoleAvailability;
import com.armada.account.model.AccountRoleAvailability.Kind;
import com.armada.account.service.impl.AccountProtocolLookupServiceImpl;
import com.armada.account.takeover.AccountTakeoverH2Support;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** B13：真实批量查询锁定可用性优先级、恢复时钟与全局身份归属边界。 */
class AccountRoleAvailabilityH2Test {
    private AccountTakeoverH2Support h;
    private AccountProtocolLookupService service;

    @BeforeEach
    void setUp() throws Exception {
        h = new AccountTakeoverH2Support();
        service = new AccountProtocolLookupServiceImpl(h.accounts, h.properties);
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void desiredOfflineAndBreakerTakePrecedenceOverOnline() {
        h.account(1, 2, 1, 2, null);
        h.account(2, 2, 1, 1, null);
        h.account(3, 2, 1, 1, null);
        trip(1, 2);

        var result = service.findRoleAvailability(List.of(1L, 2L, 3L));
        assertThat(result.get(1L).kind()).isEqualTo(Kind.TERMINAL);
        assertThat(result.get(2L).kind()).isEqualTo(Kind.TERMINAL);
        assertThat(result.get(3L).kind()).isEqualTo(Kind.ONLINE);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 8, 9})
    void terminalLifecycleWinsEvenWhenProtocolStillReportsOnline(int lifecycle) {
        h.account(1, lifecycle, 1, 1, null);
        assertThat(availability(1).kind()).isEqualTo(Kind.TERMINAL);
    }

    @Test
    void onlineMuteIsConnectedButOfflineMuteIsTerminal() {
        h.account(1, 2, 1, null, 1);
        h.account(2, 2, 2, null, 1);
        assertThat(availability(1).kind()).isEqualTo(Kind.ONLINE);
        assertThat(availability(2).kind()).isEqualTo(Kind.TERMINAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETING", "DELETED"})
    void deletionLifecycleRemainsVisibleAsTerminal(String lifecycle) {
        h.account(1, 2, 1, 1, null);
        h.reserve(1);
        h.jdbc.update("UPDATE account_creator_deletion SET lifecycle=?", lifecycle);
        AccountRoleAvailability result = availability(1);
        assertThat(result.kind()).isEqualTo(Kind.TERMINAL);
        assertThat(result.reservation().lifecycle()).isEqualTo(lifecycle);
    }

    @Test
    void reservedCreatorRecoversWithOriginalOwnerAndOfflineClock() {
        h.account(1, 6, 2, 1, null);
        h.reserve(1);
        AccountRoleAvailability result = availability(1);
        assertThat(result.accountId()).isEqualTo(1L);
        assertThat(result.kind()).isEqualTo(Kind.RECOVERING);
        assertThat(result.loginState()).isEqualTo(2);
        assertThat(result.offline()).isTrue();
        assertThat(result.offlineSince()).isEqualTo(1_000L);
        assertThat(result.reservation().tenantId()).isEqualTo(1L);
        assertThat(result.reservation().taskId()).isEqualTo(11L);
        assertThat(result.reservation().groupExecutionId()).isEqualTo(111L);
    }

    @Test
    void globalAliasReservationKeepsItsForeignTenantForRecoveryValidation() {
        h.account(1, 6, 2, 1, null);
        h.account(2, 6, 2, 1, null);
        h.reserve(2);
        h.jdbc.update("UPDATE account SET tenant_id=2,ws_phone='15500000001' WHERE id=2");
        h.jdbc.update("UPDATE account_state SET tenant_id=2 WHERE account_id=2");
        h.jdbc.update("UPDATE account_creator_deletion SET tenant_id=2,creator_phone='15500000001'");

        var result = service.findRoleAvailability(List.of(1L, 2L));
        assertThat(result).containsOnlyKeys(1L);
        assertThat(result.get(1L).kind()).isEqualTo(Kind.RECOVERING);
        assertThat(result.get(1L).reservation().tenantId()).isEqualTo(2L);
        assertThat(result.get(1L).reservation().taskId()).isEqualTo(11L);
    }

    @Test
    void filtersMissingDeletedAndForeignAccountsAndKeepsRequestOrder() {
        h.account(1, 2, 2, null, null);
        h.account(2, 2, 2, null, null);
        h.account(3, 2, 2, null, null);
        h.account(4, 2, 2, null, null);
        h.jdbc.update("UPDATE account SET deleted_at=10 WHERE id=3");
        h.jdbc.update("UPDATE account SET tenant_id=2 WHERE id=4");

        var result = service.findRoleAvailability(Arrays.asList(2L, null, 1L, 2L, 3L, 4L, 999L));
        assertThat(result.keySet()).containsExactly(2L, 1L);
        assertThat(service.findRoleAvailability(List.of())).isEmpty();
        assertThat(service.findRoleAvailability(null)).isEmpty();
    }

    @Test
    void foreignStateAndBreakerCannotChangeCurrentTenantSnapshot() {
        h.account(1, 2, 2, null, null);
        h.jdbc.update("""
                INSERT INTO account_state(tenant_id,account_id,account_state,login_state,desired_login_state)
                VALUES(2,1,3,1,2)
                """);
        trip(2, 1);
        AccountRoleAvailability result = availability(1);
        assertThat(result.kind()).isEqualTo(Kind.RECOVERING);
        assertThat(result.loginState()).isEqualTo(2);
    }

    @Test
    void unreportedStateAndNewPendingAccountsRemainRecovering() {
        h.account(1, 1, 3, null, null);
        h.account(2, 2, 2, null, null);
        h.account(3, 2, 2, null, null);
        h.jdbc.update("DELETE FROM account_state WHERE account_id=2");
        h.jdbc.update("UPDATE account_state SET account_state=NULL WHERE account_id=3");
        var result = service.findRoleAvailability(List.of(1L, 2L, 3L));
        assertThat(result.values()).extracting(AccountRoleAvailability::kind)
                .containsOnly(Kind.RECOVERING);
        assertThat(result.get(2L).offlineSince()).isNull();
        assertThat(result.get(2L).loginState()).isNull();
        assertThat(result.get(2L).offline()).isFalse();
    }

    @Test
    void disabledAccountRecoveryStopsOnlyOfflineReplacedLifecycle() {
        h.properties.setEnabled(false);
        h.account(1, 6, 2, null, null);
        h.account(2, 7, 2, null, null);
        h.account(3, 6, 1, null, null);
        assertThat(availability(1).kind()).isEqualTo(Kind.TERMINAL);
        assertThat(availability(2).kind()).isEqualTo(Kind.RECOVERING);
        assertThat(availability(3).kind()).isEqualTo(Kind.ONLINE);
    }

    @Test
    void missingTenantCannotExecuteExplicitTenantQuery() {
        h.account(1, 2, 1, null, null);
        TenantContext.clear();
        assertThatThrownBy(() -> availability(1)).isInstanceOf(BusinessException.class);
    }

    private AccountRoleAvailability availability(long id) {
        return service.findRoleAvailability(List.of(id)).get(id);
    }

    private void trip(long tenantId, long accountId) {
        h.jdbc.update("""
                INSERT INTO account_takeover_breaker(tenant_id,account_id,window_started_at,kick_count,
                  tripped_at,created_at,updated_at) VALUES(?,?,1000,10,1010,1000,1010)
                """, tenantId, accountId);
    }
}
