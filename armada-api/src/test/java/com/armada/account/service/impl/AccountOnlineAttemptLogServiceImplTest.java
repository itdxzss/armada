package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountOnlineAttemptLogMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountOnlineAttemptLog;
import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import com.armada.account.service.AccountOfflineDiagnosedEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountOnlineAttemptLogServiceImplTest {

    private final AccountOnlineAttemptLogMapper mapper = org.mockito.Mockito.mock(AccountOnlineAttemptLogMapper.class);
    private final AccountMapper accountMapper = org.mockito.Mockito.mock(AccountMapper.class);
    private final AccountOnlineAttemptLogServiceImpl service =
            new AccountOnlineAttemptLogServiceImpl(mapper, accountMapper);

    @BeforeEach
    void stubDiagnosedAccount() {
        when(accountMapper.selectActiveById(9L)).thenReturn(account(9L, 7L));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void applyOfflineDiagnosed_truncatesRawReasonAndPersistsDiagnosisLog() {
        String longReason = "x".repeat(800);
        AccountOfflineDiagnosedEvent event = event(1L, longReason, "{\"wsOpen\":false}");

        service.applyOfflineDiagnosed(event);

        ArgumentCaptor<AccountOnlineAttemptLog> captor = ArgumentCaptor.forClass(AccountOnlineAttemptLog.class);
        verify(mapper).insert(captor.capture());
        AccountOnlineAttemptLog row = captor.getValue();
        assertThat(row.getAccountId()).isEqualTo(9L);
        assertThat(row.getOnlineAttemptId()).isEqualTo("oa_1");
        assertThat(row.getRawReason()).hasSize(512);
        assertThat(row.getEvidenceJson()).isEqualTo("{\"wsOpen\":false}");
        assertThat(row.getOccurredAt()).isEqualTo("2026-07-02T10:18:00.123");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void applyOfflineDiagnosed_dropsOversizedEvidenceJson() {
        String oversizedEvidence = "{\"payload\":\"" + "x".repeat(4096) + "\"}";

        service.applyOfflineDiagnosed(event(1L, "reason", oversizedEvidence));

        ArgumentCaptor<AccountOnlineAttemptLog> captor = ArgumentCaptor.forClass(AccountOnlineAttemptLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getEvidenceJson()).isNull();
    }

    @Test
    void applyOfflineDiagnosed_keepsEvidenceJsonAtMaxLength() {
        String maxLengthEvidence = "{\"payload\":\"" + "x".repeat(4082) + "\"}";
        assertThat(maxLengthEvidence).hasSize(4096);

        service.applyOfflineDiagnosed(event(1L, "reason", maxLengthEvidence));

        ArgumentCaptor<AccountOnlineAttemptLog> captor = ArgumentCaptor.forClass(AccountOnlineAttemptLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getEvidenceJson()).isEqualTo(maxLengthEvidence);
    }

    @Test
    void applyOfflineDiagnosed_rejectsNullTenantBeforeTenantContextAndMapper() {
        assertThatThrownBy(() -> service.applyOfflineDiagnosed(event(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号离线诊断事件缺少账号定位字段");

        assertThat(TenantContext.get()).isNull();
        verifyNoInteractions(mapper);
    }

    @Test
    void applyOfflineDiagnosed_rejectsBlankDiagnosisCodeBeforeTenantContextAndMapper() {
        TenantContext.set(3L);

        assertThatThrownBy(() -> service.applyOfflineDiagnosed(eventWithDiagnosisCode(" ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号离线诊断事件缺少诊断字段");

        assertThat(TenantContext.get()).isEqualTo(3L);
        verifyNoInteractions(mapper);
    }

    @Test
    void applyOfflineDiagnosed_setsTenantContextForInsertAndClearsWhenNoPreviousTenant() {
        AtomicReference<Long> tenantDuringInsert = new AtomicReference<>();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            tenantDuringInsert.set(TenantContext.get());
            return 1;
        });

        service.applyOfflineDiagnosed(event(7L));

        assertThat(tenantDuringInsert).hasValue(7L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void applyOfflineDiagnosed_restoresPreviousTenantContextAfterInsert() {
        TenantContext.set(3L);
        AtomicReference<Long> tenantDuringInsert = new AtomicReference<>();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            tenantDuringInsert.set(TenantContext.get());
            return 1;
        });

        service.applyOfflineDiagnosed(event(7L));

        assertThat(tenantDuringInsert).hasValue(7L);
        assertThat(TenantContext.get()).isEqualTo(3L);
    }

    @Test
    void applyOfflineDiagnosed_clearsTenantContextWhenInsertThrowsAndNoPreviousTenant() {
        AtomicReference<Long> tenantDuringInsert = new AtomicReference<>();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            tenantDuringInsert.set(TenantContext.get());
            throw new IllegalStateException("insert failed");
        });

        assertThatThrownBy(() -> service.applyOfflineDiagnosed(event(7L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("insert failed");

        assertThat(tenantDuringInsert).hasValue(7L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void applyOfflineDiagnosed_restoresPreviousTenantContextWhenInsertThrows() {
        TenantContext.set(3L);
        AtomicReference<Long> tenantDuringInsert = new AtomicReference<>();
        when(mapper.insert(any())).thenAnswer(invocation -> {
            tenantDuringInsert.set(TenantContext.get());
            throw new IllegalStateException("insert failed");
        });

        assertThatThrownBy(() -> service.applyOfflineDiagnosed(event(7L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("insert failed");

        assertThat(tenantDuringInsert).hasValue(7L);
        assertThat(TenantContext.get()).isEqualTo(3L);
    }

    @Test
    void recentByAccount_clampsNonPositiveLimitToDefaultAndMapsRows() {
        when(accountMapper.selectActiveById(9L)).thenReturn(account(9L, 2L));
        when(mapper.selectRecentByAccountId(9L, 20)).thenReturn(List.of(row()));

        List<AccountOnlineAttemptLogVO> result;
        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(1L))) {
            result = service.recentByAccount(9L, 0);
        }

        verify(mapper).selectRecentByAccountId(9L, 20);
        assertThat(result).singleElement().satisfies(vo -> {
            assertThat(vo.accountId()).isEqualTo(9L);
            assertThat(vo.protocolAccountId()).isEqualTo("acc_252625852450");
            assertThat(vo.onlineAttemptId()).isEqualTo("oa_1");
            assertThat(vo.diagnosisCode()).isEqualTo("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE");
            assertThat(vo.occurredAt()).isEqualTo(1782987480123L);
            assertThat(vo.createdAt()).isEqualTo(1782987481123L);
        });
    }

    @Test
    void recentByAccount_selfOwner_canReadLogs() {
        when(accountMapper.selectActiveById(9L)).thenReturn(account(9L, 7L));
        when(mapper.selectRecentByAccountId(9L, 20)).thenReturn(List.of(row()));

        List<AccountOnlineAttemptLogVO> result;
        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.self(7L))) {
            result = service.recentByAccount(9L, 20);
        }

        assertThat(result).hasSize(1);
    }

    @Test
    void recentByAccount_otherOwner_rejectsBeforeReadingLogs() {
        when(accountMapper.selectActiveById(9L)).thenReturn(account(9L, 8L));

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.self(7L))) {
            assertThatThrownBy(() -> service.recentByAccount(9L, 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.NOT_FOUND.code());
        }

        verifyNoInteractions(mapper);
    }

    @Test
    void recentByAccount_missingScope_rejectsBeforeReadingLogs() {
        when(accountMapper.selectActiveById(9L)).thenReturn(account(9L, 7L));

        assertThatThrownBy(() -> service.recentByAccount(9L, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.ACCESS_DENIED.code());

        verifyNoInteractions(mapper);
    }

    @Test
    void recentByAccount_systemScope_rejectsBeforeReadingLogs() {
        when(accountMapper.selectActiveById(9L)).thenReturn(account(9L, 7L));

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.system("test"))) {
            assertThatThrownBy(() -> service.recentByAccount(9L, 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.ACCESS_DENIED.code());
        }

        verifyNoInteractions(mapper);
    }

    @Test
    void recentByAccount_rejectsNullAccountId() {
        assertThatThrownBy(() -> service.recentByAccount(null, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号 ID 不能为空");

        verifyNoInteractions(mapper, accountMapper);
    }

    @Test
    void timeline_clampsLimitAboveMaxAndMapsRows() {
        when(mapper.selectByAttemptId("oa_1", 200)).thenReturn(List.of(row()));
        when(accountMapper.selectActiveByIds(List.of(9L))).thenReturn(List.of(account(9L, 2L)));

        List<AccountOnlineAttemptLogVO> result;
        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(1L))) {
            result = service.timeline("oa_1", 500);
        }

        verify(mapper).selectByAttemptId("oa_1", 200);
        assertThat(result).singleElement().satisfies(vo -> {
            assertThat(vo.rawCode()).isEqualTo(408);
            assertThat(vo.evidenceJson()).isEqualTo("{\"wsOpen\":false}");
            assertThat(vo.workerId()).isEqualTo("w3");
        });
    }

    @Test
    void timeline_selfScope_validatesEveryReferencedAccountBeforeReturning() {
        when(mapper.selectByAttemptId("oa_mixed", 20))
                .thenReturn(List.of(row(9L), row(10L)));
        when(accountMapper.selectActiveByIds(List.of(9L, 10L))).thenReturn(List.of(
                account(9L, 7L),
                account(10L, 8L)));

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.self(7L))) {
            assertThatThrownBy(() -> service.timeline("oa_mixed", 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.NOT_FOUND.code());
        }
    }

    @Test
    void timeline_missingReferencedAccount_rejectsWholeTimeline() {
        when(mapper.selectByAttemptId("oa_missing", 20)).thenReturn(List.of(row(9L)));
        when(accountMapper.selectActiveByIds(List.of(9L))).thenReturn(List.of());

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(1L))) {
            assertThatThrownBy(() -> service.timeline("oa_missing", 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.NOT_FOUND.code());
        }
    }

    @Test
    void timeline_unknownAttemptIsNotFoundLikeAnInaccessibleAttempt() {
        when(mapper.selectByAttemptId("oa_unknown", 20)).thenReturn(List.of());

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(1L))) {
            assertThatThrownBy(() -> service.timeline("oa_unknown", 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.NOT_FOUND.code());
        }

        verifyNoInteractions(accountMapper);
    }

    @Test
    void timeline_missingScopeRejectsEvenWhenTimelineWouldBeEmpty() {
        assertThatThrownBy(() -> service.timeline("oa_empty", 20))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.ACCESS_DENIED.code());

        verifyNoInteractions(mapper);
    }

    @Test
    void timeline_systemScopeRejectsEvenWhenTimelineWouldBeEmpty() {
        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.system("test"))) {
            assertThatThrownBy(() -> service.timeline("oa_empty", 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(ErrorCode.ACCESS_DENIED.code());
        }

        verifyNoInteractions(mapper);
    }

    @Test
    void timeline_rejectsBlankAttemptId() {
        assertThatThrownBy(() -> service.timeline(" ", 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage("上线尝试 ID 不能为空");

        verifyNoInteractions(mapper, accountMapper);
    }

    @Test
    void timeline_rejectsNullAttemptId() {
        assertThatThrownBy(() -> service.timeline(null, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessage("上线尝试 ID 不能为空");

        verifyNoInteractions(mapper, accountMapper);
    }

    @Test
    void latestAttemptId_delegatesToMapper() {
        when(mapper.selectLatestAttemptIdByAccountId(9L)).thenReturn("oa_1");

        assertThat(service.latestAttemptId(9L)).isEqualTo("oa_1");

        verify(mapper).selectLatestAttemptIdByAccountId(9L);
    }

    @Test
    void latestProxyFailure_returnsAttemptAndProxyFromLatestProxyFailedDiagnosis() {
        when(mapper.selectLatestProxyFailureByAccountId(9L, "PROXY_FAILED")).thenReturn(row());

        var result = service.latestProxyFailure(9L);

        assertThat(result.onlineAttemptId()).isEqualTo("oa_1");
        assertThat(result.proxyId()).isEqualTo(4035L);
    }

    private static AccountOfflineDiagnosedEvent event(Long tenantId) {
        return event(tenantId, "reason", "{\"wsOpen\":false}");
    }

    private static AccountOfflineDiagnosedEvent event(Long tenantId, String rawReason, String evidenceJson) {
        return new AccountOfflineDiagnosedEvent(
                tenantId, 9L, "acc_252625852450", "oa_1", null, "cmd_1", "batch_1",
                4035L, "batch_online", "VERIFYING", "PROXY_FAILED",
                "VERIFY_TIMEOUT_NO_CONNECTION_UPDATE", "PROXY_OR_WA_CONNECTIVITY",
                408, rawReason, "RETRYABLE", "MARK_PROXY_FAILED_RELEASE_SLOT",
                1782987480123L, "w3", evidenceJson);
    }

    private static AccountOfflineDiagnosedEvent eventWithDiagnosisCode(String diagnosisCode) {
        return new AccountOfflineDiagnosedEvent(
                1L, 9L, "acc_252625852450", "oa_1", null, "cmd_1", "batch_1",
                4035L, "batch_online", "VERIFYING", "PROXY_FAILED",
                diagnosisCode, "PROXY_OR_WA_CONNECTIVITY",
                408, "reason", "RETRYABLE", "MARK_PROXY_FAILED_RELEASE_SLOT",
                1782987480123L, "w3", "{\"wsOpen\":false}");
    }

    private static AccountOnlineAttemptLog row() {
        AccountOnlineAttemptLog row = new AccountOnlineAttemptLog();
        row.setId(11L);
        row.setAccountId(9L);
        row.setProtocolAccountId("acc_252625852450");
        row.setOnlineAttemptId("oa_1");
        row.setPreviousOnlineAttemptId("oa_0");
        row.setCommandId("cmd_1");
        row.setBatchId("batch_1");
        row.setProxyId(4035L);
        row.setSource("batch_online");
        row.setFromState("VERIFYING");
        row.setToState("PROXY_FAILED");
        row.setDiagnosisCode("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE");
        row.setDiagnosisClass("PROXY_OR_WA_CONNECTIVITY");
        row.setRawCode(408);
        row.setRawReason("reason");
        row.setRecoverability("RETRYABLE");
        row.setActionTaken("MARK_PROXY_FAILED_RELEASE_SLOT");
        row.setWorkerId("w3");
        row.setEvidenceJson("{\"wsOpen\":false}");
        row.setOccurredAt(LocalDateTime.of(2026, 7, 2, 10, 18, 0, 123_000_000));
        row.setCreatedAt(LocalDateTime.of(2026, 7, 2, 10, 18, 1, 123_000_000));
        return row;
    }

    private static AccountOnlineAttemptLog row(Long accountId) {
        AccountOnlineAttemptLog row = row();
        row.setAccountId(accountId);
        return row;
    }

    private static Account account(Long accountId, Long ownerUserId) {
        Account account = new Account();
        account.setId(accountId);
        account.setOwnerUserId(ownerUserId);
        account.setProtocolAccountId("acc_252625852450");
        return account;
    }
}
