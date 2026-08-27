package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.vo.PullTaskAvatarReference;
import com.armada.task.service.impl.PullTaskMutationServiceImpl;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 拉群任务公共变更服务测试。 */
class PullTaskMutationServiceTest {

    private static final DataScope USER_SCOPE = DataScope.self(1001L);

    private final PullTaskMapper mapper = mock(PullTaskMapper.class);
    private final PullTaskStandardGroupSettingMapper settingMapper =
            mock(PullTaskStandardGroupSettingMapper.class);
    private final PullTaskGroupAvatarService avatarService = mock(PullTaskGroupAvatarService.class);
    private final PullTaskMutationService service =
            new PullTaskMutationServiceImpl(mapper, settingMapper, avatarService);

    @BeforeEach
    void openDataScope() {
        DataScopeContext.open(USER_SCOPE);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        DataScopeContext.clear();
    }

    @Test
    void emptyIdsReturnZeroWithoutCallingMapper() {
        assertThat(service.batchDelete(null)).isZero();
        assertThat(service.batchDelete(List.of())).isZero();

        verify(mapper, never()).batchSoftDeleteAllowed(anyList(), anyLong());
    }

    @Test
    void removesNullsAndDuplicatesBeforeApplyingDatabasePolicy() {
        List<Long> ids = Arrays.asList(3L, 3L, null, 2L, 3L);
        when(mapper.selectByIdsForScope(List.of(3L, 2L), USER_SCOPE))
                .thenReturn(tasks(3L, 2L));
        when(mapper.batchSoftDeleteAllowed(anyList(), anyLong())).thenReturn(2);

        assertThat(service.batchDelete(ids)).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Long> timeCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mapper).batchSoftDeleteAllowed(idsCaptor.capture(), timeCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(3L, 2L);
        assertThat(timeCaptor.getValue()).isPositive();
    }

    @Test
    void deletesCapturedAvatarOnlyAfterTransactionCommit() {
        when(mapper.selectByIdsForScope(List.of(7L), USER_SCOPE)).thenReturn(tasks(7L));
        when(settingMapper.selectActiveAvatarReferencesByTaskIds(List.of(7L)))
                .thenReturn(List.of(new PullTaskAvatarReference(3L, 7L, "avatar.png")));
        when(mapper.batchSoftDeleteAllowed(anyList(), anyLong())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        assertThat(service.batchDelete(List.of(7L))).isOne();

        verify(avatarService, never()).delete(anyLong(), org.mockito.ArgumentMatchers.anyString());
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(avatarService).deleteAfterTaskRemoval(3L, "avatar.png");
    }

    @Test
    void rolledBackDeletionKeepsAvatarFile() {
        when(mapper.selectByIdsForScope(List.of(7L), USER_SCOPE)).thenReturn(tasks(7L));
        when(settingMapper.selectActiveAvatarReferencesByTaskIds(List.of(7L)))
                .thenReturn(List.of(new PullTaskAvatarReference(3L, 7L, "avatar.png")));
        when(mapper.batchSoftDeleteAllowed(anyList(), anyLong())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.batchDelete(List.of(7L));
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(avatarService, never()).delete(anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void taskWithoutAvatarDoesNotRegisterFileDeletion() {
        when(mapper.selectByIdsForScope(List.of(7L), USER_SCOPE)).thenReturn(tasks(7L));
        when(settingMapper.selectActiveAvatarReferencesByTaskIds(List.of(7L)))
                .thenReturn(List.of());
        when(mapper.batchSoftDeleteAllowed(anyList(), anyLong())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        assertThat(service.batchDelete(List.of(7L))).isOne();

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(avatarService, never()).delete(anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void avatarDeletionFailureDoesNotEscapeCommittedTaskDeletion() {
        when(mapper.selectByIdsForScope(List.of(7L), USER_SCOPE)).thenReturn(tasks(7L));
        PullTaskAvatarReference reference =
                new PullTaskAvatarReference(3L, 7L, "avatar.png");
        when(settingMapper.selectActiveAvatarReferencesByTaskIds(List.of(7L)))
                .thenReturn(List.of(reference));
        when(mapper.batchSoftDeleteAllowed(anyList(), anyLong())).thenReturn(1);
        doThrow(new IllegalStateException("disk unavailable"))
                .when(avatarService).delete(3L, "avatar.png");
        TransactionSynchronizationManager.initSynchronization();

        assertThat(service.batchDelete(List.of(7L))).isOne();
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(avatarService).deleteAfterTaskRemoval(3L, "avatar.png");
    }

    @Test
    void mixedVisibleAndInvisibleIdsRejectEntireBatchBeforeReadingChildren() {
        when(mapper.selectByIdsForScope(List.of(7L, 8L), USER_SCOPE))
                .thenReturn(tasks(7L));

        assertThatThrownBy(() -> service.batchDelete(List.of(7L, 8L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在或无权访问");

        verify(settingMapper, never()).selectActiveAvatarReferencesByTaskIds(anyList());
        verify(mapper, never()).batchSoftDeleteAllowed(anyList(), anyLong());
    }

    private static List<PullTask> tasks(Long... ids) {
        return Arrays.stream(ids)
                .map(id -> {
                    PullTask task = new PullTask();
                    task.setId(id);
                    task.setOwnerUserId(USER_SCOPE.actorUserId());
                    return task;
                })
                .toList();
    }
}
