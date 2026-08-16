package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.recovery.ProxyFailedRecoveryCoordinator;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountStateChangedEvent;
import com.armada.group.service.GroupMetadataSyncTaskService;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

/**
 * 账号状态回写 adapter 单测。
 *
 * <p>验证 platform.kafka 入站事件会被转换为 account 域状态事件,不触碰数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class AccountStateChangedSinkAdapterTest {

    @Mock
    private AccountStateEventService service;

    @Mock
    private ProxyFailedRecoveryCoordinator recoveryCoordinator;

    @Mock
    private GroupMetadataSyncTaskService metadataSyncTaskService;

    @Mock
    private Executor metadataRecoveryExecutor;

    @InjectMocks
    private AccountStateChangedSinkAdapter adapter;

    @Test
    void handleStateChanged_mapsPlatformEventToAccountStateService() {
        ProtocolAccountStateChangedEvent platformEvent = new ProtocolAccountStateChangedEvent(
                "evt-1",
                1L,
                100L,
                "acc_861800000001",
                "ONLINE",
                "NEED_REAUTH",
                1782626401000L,
                "NEED_REAUTH",
                403,
                "batch_online",
                "oa_state_1",
                7L,
                "worker-a");
        when(service.applyStateChanged(any())).thenReturn(true);

        adapter.handleStateChanged(platformEvent);

        ArgumentCaptor<AccountStateChangedEvent> captor = ArgumentCaptor.forClass(AccountStateChangedEvent.class);
        verify(service).applyStateChanged(captor.capture());
        AccountStateChangedEvent event = captor.getValue();
        assertThat(event.tenantId()).isEqualTo(1L);
        assertThat(event.accountId()).isEqualTo(100L);
        assertThat(event.protocolAccountId()).isEqualTo("acc_861800000001");
        assertThat(event.from()).isEqualTo("ONLINE");
        assertThat(event.to()).isEqualTo("NEED_REAUTH");
        assertThat(event.occurredAt()).isEqualTo(1782626401000L);
        assertThat(event.semantic()).isEqualTo("NEED_REAUTH");
        assertThat(event.rawCode()).isEqualTo(403);
        assertThat(event.source()).isEqualTo("batch_online");
        assertThat(event.onlineAttemptId()).isEqualTo("oa_state_1");
        verify(recoveryCoordinator, never()).recover(any(), any(), any(), any());
    }

    @Test
    void handleStateChanged_appliedProxyFailedStartsRecoveryAfterStateServiceReturns() {
        ProtocolAccountStateChangedEvent platformEvent = new ProtocolAccountStateChangedEvent(
                "evt-2", 1L, 100L, "acc_861800000001", "VERIFYING", "PROXY_FAILED",
                1782626401000L, "PROXY_FAILED", 408, "batch_online", "oa_failed_1", 7L, "worker-a");
        when(service.applyStateChanged(any())).thenReturn(true);

        adapter.handleStateChanged(platformEvent);

        verify(recoveryCoordinator).recover(1L, 100L, "oa_failed_1", 7L);
    }

    @Test
    void handleStateChanged_staleProxyFailedDoesNotReleaseOrReonline() {
        ProtocolAccountStateChangedEvent platformEvent = new ProtocolAccountStateChangedEvent(
                "evt-3", 1L, 100L, "acc_861800000001", "VERIFYING", "PROXY_FAILED",
                1782626401000L, "PROXY_FAILED", 408, "batch_online", "oa_failed_1", 7L, "worker-a");
        when(service.applyStateChanged(any())).thenReturn(false);

        adapter.handleStateChanged(platformEvent);

        verify(recoveryCoordinator, never()).recover(any(), any(), any(), any());
    }

    @Test
    void handleStateChanged_appliedOnlineSubmitsMetadataResumeWithoutRunningOnConsumerThread() {
        ProtocolAccountStateChangedEvent platformEvent = new ProtocolAccountStateChangedEvent(
                "evt-online", 1L, 100L, "acc_861800000001", "VERIFYING", "ONLINE",
                1782626401000L, null, null, "batch_online", "oa_online_1", 7L, "worker-a");
        when(service.applyStateChanged(any())).thenReturn(true);

        adapter.handleStateChanged(platformEvent);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(metadataRecoveryExecutor).execute(taskCaptor.capture());
        verifyNoInteractions(metadataSyncTaskService);

        taskCaptor.getValue().run();

        verify(metadataSyncTaskService).resumeDeferredForAccount(100L, 1782626401000L);
    }

    @Test
    void handleStateChanged_metadataResumeFailureDoesNotFailStateConsumption() {
        ProtocolAccountStateChangedEvent platformEvent = new ProtocolAccountStateChangedEvent(
                "evt-online-lock", 1L, 100L, "acc_861800000001", "VERIFYING", "ONLINE",
                1782626401000L, null, null, "batch_online", "oa_online_1", 7L, "worker-a");
        when(service.applyStateChanged(any())).thenReturn(true);
        doThrow(new CannotAcquireLockException("group metadata lock timeout"))
                .when(metadataSyncTaskService).resumeDeferredForAccount(100L, 1782626401000L);

        assertThatCode(() -> adapter.handleStateChanged(platformEvent))
                .doesNotThrowAnyException();

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(metadataRecoveryExecutor).execute(taskCaptor.capture());
        assertThatCode(() -> taskCaptor.getValue().run())
                .doesNotThrowAnyException();

        verify(service).applyStateChanged(any());
        verify(metadataSyncTaskService).resumeDeferredForAccount(100L, 1782626401000L);
    }

    @Test
    void handleStateChanged_metadataResumeSubmissionRejectedDoesNotFailStateConsumption() {
        ProtocolAccountStateChangedEvent platformEvent = new ProtocolAccountStateChangedEvent(
                "evt-online-rejected", 1L, 100L, "acc_861800000001", "VERIFYING", "ONLINE",
                1782626401000L, null, null, "batch_online", "oa_online_1", 7L, "worker-a");
        when(service.applyStateChanged(any())).thenReturn(true);
        doThrow(new RejectedExecutionException("metadata recovery queue full"))
                .when(metadataRecoveryExecutor).execute(any(Runnable.class));

        assertThatCode(() -> adapter.handleStateChanged(platformEvent))
                .doesNotThrowAnyException();

        verify(service).applyStateChanged(any());
        verifyNoInteractions(metadataSyncTaskService);
    }
}
