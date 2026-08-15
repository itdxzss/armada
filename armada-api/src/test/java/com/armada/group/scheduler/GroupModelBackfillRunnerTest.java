package com.armada.group.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupModelBackfillMapper;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

/** 群组新模型 wa_group 人工回填的批次与冲突门禁。 */
class GroupModelBackfillRunnerTest {

    @Test
    void runnerRequiresExplicitOneTimeStartupFlagAndHasNoSchedule() {
        ConditionalOnProperty condition = GroupModelBackfillRunner.class.getAnnotation(
                ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("run-once");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(GroupModelBackfillRunner.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class));
    }

    @Test
    void invalidGroupSourceStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countInvalidGroupSources()).thenReturn(3);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(runner::backfillAll)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法群来源");
        verify(mapper, never()).backfillGroups(500);
    }

    @Test
    void duplicateCanonicalGroupStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countDuplicateGroupJids()).thenReturn(2);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(runner::backfillAll)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复群 JID");
        verify(mapper, never()).backfillGroups(500);
    }

    @Test
    void manualRunUsesBoundedBatchesUntilNoDataRemains() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.backfillGroups(500)).thenReturn(500, 37, 0);
        GroupModelBackfillRunner runner = runner(mapper);

        GroupModelBackfillRunner.BackfillResult result = runner.backfillAll();

        assertThat(runner).isInstanceOf(ApplicationRunner.class);
        assertThat(result.batches()).isEqualTo(2);
        assertThat(result.affectedRows()).isEqualTo(537);
        verify(mapper, times(3)).countDuplicateGroupJids();
        verify(mapper, times(3)).backfillGroups(500);
    }

    private static GroupModelBackfillRunner runner(GroupModelBackfillMapper mapper) {
        return new GroupModelBackfillRunner(
                mapper, TransactionOperations.withoutTransaction());
    }
}
