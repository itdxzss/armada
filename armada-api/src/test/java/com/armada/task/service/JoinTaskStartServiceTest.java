package com.armada.task.service;

import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.enums.JoinTaskStatus;
import com.armada.task.service.impl.JoinTaskServiceImpl;
import com.armada.task.worker.JoinTaskWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JoinTaskStartServiceTest {

    @Mock
    private JoinTaskMapper joinTaskMapper;

    @Mock
    private JoinTaskResultMapper resultMapper;

    @Mock
    private GroupLinkRegistryService groupLinkRegistryService;

    @Mock
    private JoinTaskWorker joinTaskWorker;

    private JoinTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        service = new JoinTaskServiceImpl(joinTaskMapper, resultMapper, groupLinkRegistryService, joinTaskWorker);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void startTask_movesDraftToRunningAndTriggersWorker() {
        JoinTask task = new JoinTask();
        task.setId(42L);
        task.setStatus(JoinTaskStatus.DRAFT);
        when(joinTaskMapper.selectByTenantAndId(42L)).thenReturn(task);

        service.startTask(42L);

        verify(joinTaskMapper).updateTaskStatus(eq(42L), eq(JoinTaskStatus.RUNNING), anyLong());
        verify(joinTaskWorker).startAsync(1L, 42L);
    }

    @Test
    void startTask_rejectsNonDraftTask() {
        JoinTask task = new JoinTask();
        task.setId(43L);
        task.setStatus(JoinTaskStatus.RUNNING);
        when(joinTaskMapper.selectByTenantAndId(43L)).thenReturn(task);

        assertThatThrownBy(() -> service.startTask(43L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION.code()));

        verify(joinTaskMapper, never()).updateTaskStatus(eq(43L), eq(JoinTaskStatus.RUNNING), anyLong());
        verify(joinTaskWorker, never()).startAsync(1L, 43L);
    }
}
