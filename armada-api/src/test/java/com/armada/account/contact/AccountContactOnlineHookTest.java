package com.armada.account.contact;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.account.contact.service.AccountContactOnlineHook;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountContactOnlineHookTest {

    private static final Executor DIRECT = Runnable::run;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void syncsOnceOnOnlineAndRestoresTenantContext() {
        AccountContactSyncService service = mock(AccountContactSyncService.class);
        when(service.syncIfStale(eq(501L), any()))
                .thenReturn(new AccountContactSyncResult(true, true, 3, 3, 0, 1L, null));
        AccountContactOnlineHook hook = new AccountContactOnlineHook(
                service, new AccountContactProperties(true, 24), DIRECT);

        hook.onAccountOnline(7L, 501L);

        verify(service).syncIfStale(501L, ContactSyncSource.ONLINE_EVENT);
        // 附属任务不能污染调用线程的租户上下文
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void syncFailureNeverPropagatesToTheCaller() {
        AccountContactSyncService service = mock(AccountContactSyncService.class);
        doThrow(new IllegalStateException("协议不可用"))
                .when(service).syncIfStale(any(), any());
        AccountContactOnlineHook hook = new AccountContactOnlineHook(
                service, new AccountContactProperties(true, 24), DIRECT);

        // 通讯录同步是 ONLINE 附属任务，失败绝不能反向阻塞账号状态 Kafka 分区
        assertThatCode(() -> hook.onAccountOnline(7L, 501L)).doesNotThrowAnyException();
    }

    @Test
    void executorRejectionNeverPropagatesToTheCaller() {
        AccountContactSyncService service = mock(AccountContactSyncService.class);
        Executor rejecting = task -> {
            throw new RejectedExecutionException("队列已满");
        };
        AccountContactOnlineHook hook = new AccountContactOnlineHook(
                service, new AccountContactProperties(true, 24), rejecting);

        assertThatCode(() -> hook.onAccountOnline(7L, 501L)).doesNotThrowAnyException();
        verify(service, never()).syncIfStale(any(), any());
    }

    @Test
    void disabledByConfigurationSkipsSyncEntirely() {
        AccountContactSyncService service = mock(AccountContactSyncService.class);
        AccountContactOnlineHook hook = new AccountContactOnlineHook(
                service, new AccountContactProperties(false, 24), DIRECT);

        hook.onAccountOnline(7L, 501L);

        verify(service, never()).syncIfStale(any(), any());
    }
}
