package com.armada.group.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupModelBackfillMapper;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** 群组新模型 wa_group 存量回填任务的批次与冲突门禁。 */
class GroupModelBackfillJobTest {

    @Test
    void invalidGroupSourceStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countInvalidGroupSources()).thenReturn(3);
        GroupModelBackfillJob job = new GroupModelBackfillJob(mapper);

        assertThatThrownBy(job::backfillOnce)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法群来源");
        verify(mapper, never()).backfillGroups(500);
    }

    @Test
    void duplicateCanonicalGroupStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countDuplicateGroupJids()).thenReturn(2);
        GroupModelBackfillJob job = new GroupModelBackfillJob(mapper);

        assertThatThrownBy(job::backfillOnce)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复群 JID");
        verify(mapper, never()).backfillGroups(500);
    }

    @Test
    void oneRunUsesSingleBoundedSetBasedGroupWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.backfillGroups(500)).thenReturn(37);
        GroupModelBackfillJob job = new GroupModelBackfillJob(mapper);

        GroupModelBackfillJob.BackfillResult result = job.backfillOnce();

        assertThat(result.groupRows()).isEqualTo(37);
        verify(mapper).countDuplicateGroupJids();
        verify(mapper).backfillGroups(500);
    }
}
