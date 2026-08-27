package com.armada.account.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.entity.ImportResult;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.security.DataScopeMode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 跨租户导入派发器的 tenant/DataScope 边界测试。 */
@ExtendWith(MockitoExtension.class)
class AccountImportOnlineDispatcherTest {

    @Mock
    private AccountImportDetailMapper detailMapper;

    @Mock
    private AccountImportOnlineDispatchWorker worker;

    @InjectMocks
    private AccountImportOnlineDispatcher dispatcher;

    @AfterEach
    void clearContexts() {
        DataScopeContext.clear();
        TenantContext.clear();
    }

    @Test
    void dispatchOnce_opensSystemScopePerTenantAndRestoresCallerContexts() {
        when(detailMapper.selectQueuedTenantIds(
                AccountImportOnlineDispatchWorker.QUEUED_PHASE,
                ImportResult.SUCCESS.getCode(), 100))
                .thenReturn(List.of(7L, 8L));
        List<Long> observedTenants = new ArrayList<>();
        when(worker.dispatchTenantBatch(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    observedTenants.add(TenantContext.get());
                    DataScope scope = DataScopeContext.requireCurrent();
                    assertThat(scope.mode()).isEqualTo(DataScopeMode.SYSTEM);
                    assertThat(scope.systemReason()).isEqualTo("account import online dispatch");
                    return 1;
                });
        TenantContext.set(99L);
        DataScope callerScope = DataScope.all(5L);
        DataScopeContext.open(callerScope);

        assertThat(dispatcher.dispatchOnce()).isEqualTo(2);

        assertThat(observedTenants).containsExactly(7L, 8L);
        assertThat(TenantContext.get()).isEqualTo(99L);
        assertThat(DataScopeContext.requireCurrent()).isEqualTo(callerScope);
    }
}
