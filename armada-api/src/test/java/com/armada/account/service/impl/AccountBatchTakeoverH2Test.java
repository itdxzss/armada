package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.takeover.AccountTakeoverH2Support;
import com.armada.platform.protocol.exception.ProtocolAccountCommandRejectedException;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 真实 outbox 和 Spring 事务验证抢登的拒绝隔离与状态/熔断回滚。 */
class AccountBatchTakeoverH2Test {
    private AccountTakeoverH2Support h;
    private AccountBatchLifecycleServiceImpl service;
    @BeforeEach void setup() throws Exception {
        h = new AccountTakeoverH2Support();
        for (long id : List.of(1L, 2L, 3L)) h.account(id, 6, 2, 2, null);
        h.reserve(1);
        service = new AccountBatchLifecycleServiceImpl(h.accounts, h.online);
        h.properties.setEnabled(true);
        h.jdbc.update("INSERT INTO account_takeover_breaker(tenant_id,account_id,kick_count,window_started_at,"
                + "tripped_at,last_kicked_at,created_at,updated_at) VALUES(1,1,3,100,200,200,100,200)");
    }
    @AfterEach void cleanup() { TenantContext.clear(); }

    @Test void mixedTakeoverCommitsTwoAndRollsBackRestrictedStateAndBreakerReset() {
        var breaker = h.jdbc.queryForMap("SELECT * FROM account_takeover_breaker WHERE account_id=1");
        var state = h.jdbc.queryForMap("SELECT * FROM account_state WHERE account_id=1");
        var result = service.takeoverByIds(List.of(1L, 2L, 3L));
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.failed()).isOne();
        assertThat(result.batchErrors()).containsExactly("账号 1：账号已被一次性建群任务预留或进入永久注销流程");
        assertThat(h.jdbc.queryForMap("SELECT * FROM account_state WHERE account_id=1")).isEqualTo(state);
        assertThat(h.jdbc.queryForMap("SELECT * FROM account_takeover_breaker WHERE account_id=1")).isEqualTo(breaker);
        assertThat(h.jdbc.queryForList("SELECT aggregate_id FROM protocol_command_outbox ORDER BY aggregate_id", Long.class)).containsExactly(2L, 3L);
    }

    @Test void allRestrictedTerminatesWithoutAcceptance() {
        var result = service.takeoverByIds(List.of(1L));
        assertThat(result.accepted()).isZero();
        assertThat(result.failed()).isOne();
        assertThat(h.jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Integer.class)).isZero();
    }

    @Test void ordinaryFailureIsNeverRetried() {
        var commands = mock(AccountOnlineCommandService.class);
        var ids = List.of(1L, 2L, 3L);
        when(commands.takeoverBatch(ids)).thenThrow(new IllegalStateException("database unavailable"));
        var result = new AccountBatchLifecycleServiceImpl(h.accounts, commands).takeoverByIds(ids);
        assertThat(result.failed()).isEqualTo(3);
        verify(commands).takeoverBatch(ids);
        verifyNoMoreInteractions(commands);
    }

    @Test void invalidRejectedSetStopsRatherThanLooping() {
        var commands = mock(AccountOnlineCommandService.class);
        var ids = List.of(1L, 2L, 3L);
        when(commands.takeoverBatch(ids)).thenThrow(new ProtocolAccountCommandRejectedException(List.of(9L)));
        var result = new AccountBatchLifecycleServiceImpl(h.accounts, commands).takeoverByIds(ids);
        assertThat(result.failed()).isEqualTo(3);
        verify(commands).takeoverBatch(ids);
        verifyNoMoreInteractions(commands);
    }
}
