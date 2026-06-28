package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.vo.AccountProbeVO;
import com.armada.account.model.vo.AccountStatusVO;
import com.armada.platform.protocol.model.result.ProtocolAccountStatus;
import com.armada.platform.protocol.model.result.ProtocolProbeResult;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 账号生命周期诊断服务单测。
 *
 * <p>只覆盖账号域编排:account.id -> protocolAccountId -> AccountLifecyclePort。
 * 协议 HTTP 细节由 adapter 测试覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountLifecycleCommandServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountLifecyclePort accountLifecyclePort;

    @InjectMocks
    private AccountLifecycleCommandServiceImpl service;

    @Test
    void refreshStatus_loadsAccountAndQueriesProtocolStatusByProtocolAccountId() {
        Account account = account(100L, "acc_8613800138000");
        ProtocolAccountStatus status = new ProtocolAccountStatus(
                "acc_8613800138000",
                "ONLINE",
                "HEARTBEAT",
                "BUSINESS_STANDARD",
                Instant.parse("2026-06-28T10:00:00Z"),
                null,
                Instant.parse("2026-06-28T10:00:01Z"),
                false,
                null,
                "worker-a");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(accountLifecyclePort.status("acc_8613800138000")).thenReturn(status);

        AccountStatusVO result = service.refreshStatus(100L);

        verify(accountLifecyclePort).status("acc_8613800138000");
        assertThat(result.accountId()).isEqualTo(100L);
        assertThat(result.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(result.state()).isEqualTo("ONLINE");
        assertThat(result.stateSource()).isEqualTo("HEARTBEAT");
        assertThat(result.accountType()).isEqualTo("BUSINESS_STANDARD");
        assertThat(result.lastStateSyncTime()).isEqualTo(Instant.parse("2026-06-28T10:00:00Z").toEpochMilli());
        assertThat(result.cooldownUntil()).isNull();
        assertThat(result.reportedAt()).isEqualTo(Instant.parse("2026-06-28T10:00:01Z").toEpochMilli());
        assertThat(result.needReauth()).isFalse();
        assertThat(result.workerId()).isEqualTo("worker-a");
    }

    @Test
    void probe_loadsAccountAndProbesProtocolByProtocolAccountId() {
        Account account = account(100L, "acc_8613800138000");
        ProtocolProbeResult probe = new ProtocolProbeResult(
                true,
                Instant.parse("2026-06-28T10:01:00Z"),
                186L,
                "OK");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(accountLifecyclePort.probe("acc_8613800138000")).thenReturn(probe);

        AccountProbeVO result = service.probe(100L);

        verify(accountLifecyclePort).probe("acc_8613800138000");
        assertThat(result.accountId()).isEqualTo(100L);
        assertThat(result.protocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(result.ok()).isTrue();
        assertThat(result.probedAt()).isEqualTo(Instant.parse("2026-06-28T10:01:00Z").toEpochMilli());
        assertThat(result.latencyMs()).isEqualTo(186L);
        assertThat(result.reasonCode()).isEqualTo("OK");
    }

    @Test
    void refreshStatus_rejectsMissingAccount() {
        when(accountMapper.selectActiveById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.refreshStatus(404L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOT_FOUND.code());
    }

    @Test
    void probe_rejectsBlankProtocolAccountId() {
        Account account = account(100L, " ");
        when(accountMapper.selectActiveById(100L)).thenReturn(account);

        assertThatThrownBy(() -> service.probe(100L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.VALIDATION.code());
    }

    private static Account account(Long id, String protocolAccountId) {
        Account account = new Account();
        account.setId(id);
        account.setProtocolAccountId(protocolAccountId);
        return account;
    }
}
