package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.armada.account.mapper.AccountOnlineAttemptLogMapper;
import com.armada.account.model.entity.AccountOnlineAttemptLog;
import com.armada.account.service.AccountOfflineDiagnosedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountOnlineAttemptLogServiceImplTest {

    private final AccountOnlineAttemptLogMapper mapper = org.mockito.Mockito.mock(AccountOnlineAttemptLogMapper.class);
    private final AccountOnlineAttemptLogServiceImpl service = new AccountOnlineAttemptLogServiceImpl(mapper);

    @Test
    void applyOfflineDiagnosed_truncatesRawReasonAndPersistsDiagnosisLog() {
        String longReason = "x".repeat(800);
        AccountOfflineDiagnosedEvent event = new AccountOfflineDiagnosedEvent(
                1L, 9L, "acc_252625852450", "oa_1", null, "cmd_1", "batch_1",
                4035L, "batch_online", "VERIFYING", "PROXY_FAILED",
                "VERIFY_TIMEOUT_NO_CONNECTION_UPDATE", "PROXY_OR_WA_CONNECTIVITY",
                408, longReason, "RETRYABLE", "MARK_PROXY_FAILED_RELEASE_SLOT",
                1782987480123L, "w3", "{\"wsOpen\":false}");

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
}
