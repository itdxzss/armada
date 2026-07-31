package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.service.impl.PullTaskMutationServiceImpl;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 拉群任务公共变更服务测试。 */
class PullTaskMutationServiceTest {

    private final PullTaskMapper mapper = mock(PullTaskMapper.class);
    private final PullTaskMutationService service = new PullTaskMutationServiceImpl(mapper);

    @Test
    void emptyIdsReturnZeroWithoutCallingMapper() {
        assertThat(service.batchDelete(null)).isZero();
        assertThat(service.batchDelete(List.of())).isZero();

        verify(mapper, never()).batchSoftDeleteAllowed(anyList(), anyLong());
    }

    @Test
    void removesNullsAndDuplicatesBeforeApplyingDatabasePolicy() {
        List<Long> ids = Arrays.asList(3L, 3L, null, 2L, 3L);
        when(mapper.batchSoftDeleteAllowed(anyList(), anyLong())).thenReturn(2);

        assertThat(service.batchDelete(ids)).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Long> timeCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mapper).batchSoftDeleteAllowed(idsCaptor.capture(), timeCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(3L, 2L);
        assertThat(timeCaptor.getValue()).isPositive();
    }
}
