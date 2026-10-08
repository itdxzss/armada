package com.armada.account.takeover;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 使用真实账号、熔断和 Outbox Mapper 验证自动与人工抢登的命令边界。 */
class AccountAutoTakeoverOnlineH2Test {

    private AccountTakeoverH2Support support;

    @BeforeEach
    void setUp() throws Exception {
        support = new AccountTakeoverH2Support();
        TenantContext.set(1L);
        support.account(1L, 6, 2, 1, null);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void automaticTakeoverPersistsTakingOverAndOneOnlineCommand() {
        assertThat(support.online.autoTakeover(1L).submitted()).isEqualTo(1);
        assertThat(support.jdbc.queryForObject(
                "SELECT account_state FROM account_state WHERE account_id=1", Integer.class)).isEqualTo(7);
        assertThat(commandCount()).isEqualTo(1);
        assertThat(support.jdbc.queryForObject(
                "SELECT payload_json FROM protocol_command_outbox", String.class)).contains("login_replaced_takeover");
        assertThat(support.online.autoTakeover(1L).submitted()).isZero();
        assertThat(commandCount()).isEqualTo(1);
    }

    @Test
    void automaticTakeoverPreservesExplicitStopAndCannotCrossTenant() {
        support.jdbc.update("UPDATE account_state SET desired_login_state=2 WHERE account_id=1");
        assertThat(support.online.autoTakeover(1L).submitted()).isZero();
        assertThat(commandCount()).isZero();
        assertThat(support.jdbc.queryForObject(
                "SELECT desired_login_state FROM account_state WHERE account_id=1", Integer.class)).isEqualTo(2);
        TenantContext.set(8L);
        assertThat(support.online.autoTakeover(1L).submitted()).isZero();
    }

    @Test
    void takingOverReonlineRejectsExplicitStop() {
        support.jdbc.update("UPDATE account_state SET account_state=7,desired_login_state=2 WHERE account_id=1");
        assertThat(support.online.reonlineForTakeover(1L, null, "login_replaced_takeover").accepted()).isFalse();
        assertThat(commandCount()).isZero();
    }

    @Test
    void manualTakeoverResetsBreakerWhereAutomaticTakeoverCannot() {
        trip();
        assertThat(support.online.autoTakeover(1L).submitted()).isZero();
        assertThat(support.online.takeoverBatch(List.of(1L)).submitted()).isEqualTo(1);
        assertThat(support.breaker.isTripped(1L)).isFalse();
        assertThat(support.jdbc.queryForObject(
                "SELECT kick_count FROM account_takeover_breaker WHERE account_id=1", Integer.class)).isZero();
        assertThat(commandCount()).isEqualTo(1);
    }

    @Test
    void disabledAccountSwitchSkipsAutomaticTakeoverAndPreservesManualBreaker() {
        trip();
        support.properties.setEnabled(false);
        assertThat(support.online.autoTakeover(1L).submitted()).isZero();
        assertThat(support.online.takeoverBatch(List.of(1L)).submitted()).isEqualTo(1);
        assertThat(support.breaker.isTripped(1L)).isTrue();
    }

    @Test
    void manualFailureRollsBackBreakerReset() {
        trip();
        support.jdbc.update("UPDATE account_state SET mute_status=1 WHERE account_id=1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> support.online.takeoverBatch(List.of(1L)))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class);
        assertThat(support.breaker.isTripped(1L)).isTrue();
        assertThat(commandCount()).isZero();
    }

    private void trip() {
        support.properties.setBreakerMaxKicks(1);
        support.breaker.recordKick(support.accounts.selectActiveById(1L), 1_000L);
    }

    private int commandCount() {
        return support.jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Integer.class);
    }
}
