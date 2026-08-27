package com.armada.account.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountImportBatchMapper;
import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 账号导入自动上线单租户派发的 owner 继承测试。 */
@ExtendWith(MockitoExtension.class)
class AccountImportOnlineDispatchWorkerTest {

    @Mock
    private AccountImportDetailMapper detailMapper;

    @Mock
    private AccountImportBatchMapper batchMapper;

    @Mock
    private AccountOnlineCommandService onlineCommandService;

    @InjectMocks
    private AccountImportOnlineDispatchWorker worker;

    @Test
    void dispatchTenantBatch_usesPersistedBatchOwnerAndMarksOnlyAcceptedDetails() {
        List<AccountImportDetail> details = List.of(
                detail(1L, 10L, 100L),
                detail(2L, 10L, 101L));
        AccountImportBatch batch = batch(10L, 9L);
        when(detailMapper.selectQueuedForUpdate(
                7L, AccountImportOnlinePhase.QUEUED, ImportResult.SUCCESS.getCode(), 500))
                .thenReturn(details);
        when(batchMapper.selectById(10L)).thenReturn(batch);
        when(onlineCommandService.onlineBatch(List.of(100L, 101L))).thenAnswer(ignored -> {
            assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(9L);
            return accepted(2);
        });
        when(detailMapper.markDispatched(
                eq(List.of(1L, 2L)), eq(AccountImportOnlinePhase.QUEUED),
                eq(AccountImportOnlinePhase.DISPATCHED), anyLong()))
                .thenReturn(2);

        assertThat(worker.dispatchTenantBatch(7L)).isEqualTo(2);

        verify(onlineCommandService).onlineBatch(List.of(100L, 101L));
        verify(detailMapper).markDispatched(
                eq(List.of(1L, 2L)), eq(AccountImportOnlinePhase.QUEUED),
                eq(AccountImportOnlinePhase.DISPATCHED), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispatchTenantBatch_mixedBatchLockResultFailsBeforeOwnerLookupAndOnline() {
        when(detailMapper.selectQueuedForUpdate(
                7L, AccountImportOnlinePhase.QUEUED, ImportResult.SUCCESS.getCode(), 500))
                .thenReturn(List.of(detail(1L, 10L, 100L), detail(2L, 11L, 101L)));

        assertThatThrownBy(() -> worker.dispatchTenantBatch(7L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code());
                    assertThat(ex.getMessage()).contains("单批次");
                });

        verifyNoInteractions(batchMapper, onlineCommandService);
        verify(detailMapper, never()).markDispatched(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispatchTenantBatch_unownedHistoricalBatchFailsClosed() {
        when(detailMapper.selectQueuedForUpdate(
                7L, AccountImportOnlinePhase.QUEUED, ImportResult.SUCCESS.getCode(), 500))
                .thenReturn(List.of(detail(1L, 10L, 100L)));
        when(batchMapper.selectById(10L)).thenReturn(batch(10L, null));

        assertThatThrownBy(() -> worker.dispatchTenantBatch(7L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code());
                    assertThat(ex.getMessage()).contains("可信 owner");
                });

        verifyNoInteractions(onlineCommandService);
    }

    @Test
    void dispatchTenantBatch_partialOutboxAcceptanceDoesNotAdvanceDetails() {
        when(detailMapper.selectQueuedForUpdate(
                7L, AccountImportOnlinePhase.QUEUED, ImportResult.SUCCESS.getCode(), 500))
                .thenReturn(List.of(detail(1L, 10L, 100L), detail(2L, 10L, 101L)));
        when(batchMapper.selectById(10L)).thenReturn(batch(10L, 9L));
        when(onlineCommandService.onlineBatch(List.of(100L, 101L)))
                .thenReturn(accepted(1));

        assertThatThrownBy(() -> worker.dispatchTenantBatch(7L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code());
                    assertThat(ex.getMessage()).contains("受理数量不一致");
                });

        verify(detailMapper, never()).markDispatched(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static AccountImportDetail detail(long id, long batchId, long accountId) {
        AccountImportDetail detail = new AccountImportDetail();
        detail.setId(id);
        detail.setBatchId(batchId);
        detail.setAccountId(accountId);
        return detail;
    }

    private static AccountImportBatch batch(long id, Long ownerUserId) {
        AccountImportBatch batch = new AccountImportBatch();
        batch.setId(id);
        batch.setOwnerUserId(ownerUserId);
        return batch;
    }

    private static AccountBatchOnlineVO accepted(int count) {
        return new AccountBatchOnlineVO(count, count, count, 0, 0, 0, 0, 0L, List.of(), List.of());
    }
}
