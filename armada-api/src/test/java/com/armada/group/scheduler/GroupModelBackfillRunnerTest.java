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
    void inviteConflictStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countInviteConflicts()).thenReturn(2);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(runner::backfillAll)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邀请冲突");
        verify(mapper, never()).backfillGroups(500);
        verify(mapper, never()).backfillProfiles(500);
        verify(mapper, never()).backfillInvites(500);
    }

    @Test
    void participantConflictStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countParticipantConflicts()).thenReturn(2);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(runner::backfillAll)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成员身份冲突");
        verify(mapper, never()).backfillGroups(500);
        verify(mapper, never()).backfillParticipants(500);
    }

    @Test
    void ambiguousBaselineStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countBindingConflicts()).thenReturn(1);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(runner::backfillAll)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("baseline 冲突");
        verify(mapper, never()).backfillGroups(500);
        verify(mapper, never()).backfillAccountGroupBindings(500);
    }

    @Test
    void manualRunUsesBoundedBatchesUntilNoDataRemains() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.backfillGroups(500)).thenReturn(500, 37, 0);
        when(mapper.backfillProfiles(500)).thenReturn(20, 0);
        when(mapper.backfillMemberSnapshotHeaders(500)).thenReturn(2, 0);
        when(mapper.backfillInvites(500)).thenReturn(10, 0);
        when(mapper.backfillCurrentInvitePointers(500)).thenReturn(8, 0);
        when(mapper.backfillProfileOwners(500)).thenReturn(1, 0);
        when(mapper.backfillParticipants(500)).thenReturn(40, 0);
        when(mapper.backfillAccountParticipants(500)).thenReturn(5, 0);
        when(mapper.backfillParticipantJoinFacts(500)).thenReturn(4, 0);
        when(mapper.backfillParticipantExitFacts(500)).thenReturn(3, 0);
        when(mapper.backfillAccountGroupBindings(500)).thenReturn(6, 0);
        when(mapper.backfillAccountGroupSyncStates(500)).thenReturn(2, 0);
        GroupModelBackfillRunner runner = runner(mapper);

        GroupModelBackfillRunner.BackfillResult result = runner.backfillAll();

        assertThat(runner).isInstanceOf(ApplicationRunner.class);
        assertThat(result.batches()).isEqualTo(13);
        assertThat(result.affectedRows()).isEqualTo(638);
        verify(mapper, times(25)).countDuplicateGroupJids();
        verify(mapper, times(25)).countParticipantConflicts();
        verify(mapper, times(25)).countBindingConflicts();
        verify(mapper, times(3)).backfillGroups(500);
        verify(mapper, times(2)).backfillProfiles(500);
        verify(mapper, times(2)).backfillMemberSnapshotHeaders(500);
        verify(mapper, times(2)).backfillInvites(500);
        verify(mapper, times(2)).backfillCurrentInvitePointers(500);
        verify(mapper, times(2)).backfillProfileOwners(500);
        verify(mapper, times(2)).backfillParticipants(500);
        verify(mapper, times(2)).backfillAccountParticipants(500);
        verify(mapper, times(2)).backfillParticipantJoinFacts(500);
        verify(mapper, times(2)).backfillParticipantExitFacts(500);
        verify(mapper, times(2)).backfillAccountGroupBindings(500);
        verify(mapper, times(2)).backfillAccountGroupSyncStates(500);
    }

    private static GroupModelBackfillRunner runner(GroupModelBackfillMapper mapper) {
        return new GroupModelBackfillRunner(
                mapper, TransactionOperations.withoutTransaction());
    }
}
