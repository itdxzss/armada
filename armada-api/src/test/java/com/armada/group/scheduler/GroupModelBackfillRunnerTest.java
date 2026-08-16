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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
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
        assertThat(GroupModelBackfillRunner.class.getConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(
                        constructor.isAnnotationPresent(Autowired.class)).isTrue());
    }

    @Test
    void invalidGroupSourceStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countInvalidGroupSources()).thenReturn(3);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(() -> runner.backfillFrom(
                GroupModelBackfillRunner.BackfillStage.GROUPS,
                GroupModelBackfillRunner.BackfillStage.ACCOUNT_GROUP_SYNC_STATES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法群来源");
        verify(mapper, never()).backfillGroups(50_000);
    }

    @Test
    void duplicateCanonicalGroupStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countDuplicateGroupJids()).thenReturn(2);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(() -> runner.backfillFrom(
                GroupModelBackfillRunner.BackfillStage.GROUPS,
                GroupModelBackfillRunner.BackfillStage.ACCOUNT_GROUP_SYNC_STATES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复群 JID");
        verify(mapper, never()).backfillGroups(50_000);
    }

    @Test
    void inviteConflictStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countInviteConflicts()).thenReturn(2);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(() -> runner.backfillFrom(
                GroupModelBackfillRunner.BackfillStage.GROUPS,
                GroupModelBackfillRunner.BackfillStage.ACCOUNT_GROUP_SYNC_STATES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邀请冲突");
        verify(mapper, never()).backfillGroups(50_000);
        verify(mapper, never()).backfillProfiles(50_000);
        verify(mapper, never()).backfillInvites(50_000);
    }

    @Test
    void participantConflictStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countParticipantConflicts()).thenReturn(2);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(() -> runner.backfillFrom(
                GroupModelBackfillRunner.BackfillStage.GROUPS,
                GroupModelBackfillRunner.BackfillStage.ACCOUNT_GROUP_SYNC_STATES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成员身份冲突");
        verify(mapper, never()).backfillGroups(50_000);
        verify(mapper, never()).backfillParticipants(50_000);
    }

    @Test
    void ambiguousBaselineStopsBeforeAnyWrite() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.countBindingConflicts()).thenReturn(1);
        GroupModelBackfillRunner runner = runner(mapper);

        assertThatThrownBy(() -> runner.backfillFrom(
                GroupModelBackfillRunner.BackfillStage.GROUPS,
                GroupModelBackfillRunner.BackfillStage.ACCOUNT_GROUP_SYNC_STATES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("baseline 冲突");
        verify(mapper, never()).backfillGroups(50_000);
        verify(mapper, never()).backfillAccountGroupBindings(50_000);
    }

    @Test
    void manualRunStopsAfterFinalPartialBatch() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.backfillGroups(50_000)).thenReturn(50_000, 37);
        when(mapper.backfillProfiles(50_000)).thenReturn(20);
        when(mapper.backfillMemberSnapshotHeaders(50_000)).thenReturn(2);
        when(mapper.backfillInvites(50_000)).thenReturn(10);
        when(mapper.backfillCurrentInvitePointers(50_000)).thenReturn(8);
        when(mapper.backfillProfileOwners(50_000)).thenReturn(1);
        when(mapper.selectLegacyMemberSnapshotBatchEndId(0, 5_000))
                .thenReturn(40_000L);
        when(mapper.selectLegacyMemberSnapshotBatchEndId(40_000, 5_000))
                .thenReturn(null);
        when(mapper.backfillLegacyMemberSnapshots(0, 40_000)).thenReturn(30);
        when(mapper.backfillParticipants(50_000)).thenReturn(40);
        when(mapper.backfillAccountParticipants(50_000)).thenReturn(5);
        when(mapper.backfillParticipantJoinFacts(50_000)).thenReturn(4);
        when(mapper.backfillParticipantExitFacts(50_000)).thenReturn(3);
        when(mapper.backfillAccountGroupBindings(50_000)).thenReturn(6);
        when(mapper.backfillAccountGroupSyncStates(50_000)).thenReturn(2);
        GroupModelBackfillRunner runner = runner(mapper);

        GroupModelBackfillRunner.BackfillResult result = runner.backfillFrom(
                GroupModelBackfillRunner.BackfillStage.GROUPS,
                GroupModelBackfillRunner.BackfillStage.ACCOUNT_GROUP_SYNC_STATES);

        assertThat(runner).isInstanceOf(ApplicationRunner.class);
        assertThat(result.batches()).isEqualTo(14);
        assertThat(result.affectedRows()).isEqualTo(50_168);
        verify(mapper, times(2)).countInvalidGroupSources();
        verify(mapper, times(2)).countDuplicateGroupJids();
        verify(mapper, times(2)).countInviteConflicts();
        verify(mapper, times(2)).countParticipantConflicts();
        verify(mapper, times(2)).countBindingConflicts();
        verify(mapper, times(2)).backfillGroups(50_000);
        verify(mapper).backfillProfiles(50_000);
        verify(mapper).backfillMemberSnapshotHeaders(50_000);
        verify(mapper).backfillInvites(50_000);
        verify(mapper).backfillCurrentInvitePointers(50_000);
        verify(mapper).backfillProfileOwners(50_000);
        verify(mapper).backfillLegacyMemberSnapshots(0, 40_000);
        verify(mapper).backfillParticipants(50_000);
        verify(mapper).backfillAccountParticipants(50_000);
        verify(mapper).backfillParticipantJoinFacts(50_000);
        verify(mapper).backfillParticipantExitFacts(50_000);
        verify(mapper).backfillAccountGroupBindings(50_000);
        verify(mapper).backfillAccountGroupSyncStates(50_000);
    }

    @Test
    void manualRunCanResumeAtAccountGroupBindings() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.backfillAccountGroupBindings(50_000)).thenReturn(6);
        when(mapper.backfillAccountGroupSyncStates(50_000)).thenReturn(2);
        GroupModelBackfillRunner runner = runner(mapper);

        runner.run(new DefaultApplicationArguments(
                "--armada.group-model-backfill.start-stage=account-group-bindings"));

        verify(mapper, never()).backfillGroups(50_000);
        verify(mapper, never()).backfillAccountParticipants(50_000);
        verify(mapper, never()).backfillParticipantExitFacts(50_000);
        verify(mapper).backfillAccountGroupBindings(50_000);
        verify(mapper).backfillAccountGroupSyncStates(50_000);
    }

    @Test
    void manualRunCanStopAfterProfiles() {
        GroupModelBackfillMapper mapper = mock(GroupModelBackfillMapper.class);
        when(mapper.backfillGroups(50_000)).thenReturn(3);
        when(mapper.backfillProfiles(50_000)).thenReturn(4);
        GroupModelBackfillRunner runner = runner(mapper);

        runner.run(new DefaultApplicationArguments(
                "--armada.group-model-backfill.start-stage=groups",
                "--armada.group-model-backfill.end-stage=profiles"));

        verify(mapper).backfillGroups(50_000);
        verify(mapper).backfillProfiles(50_000);
        verify(mapper, never()).backfillMemberSnapshotHeaders(50_000);
        verify(mapper, never()).selectLegacyMemberSnapshotBatchEndId(0, 5_000);
        verify(mapper, never()).countInviteConflicts();
        verify(mapper, never()).countParticipantConflicts();
        verify(mapper, never()).countBindingConflicts();
    }

    private static GroupModelBackfillRunner runner(GroupModelBackfillMapper mapper) {
        return new GroupModelBackfillRunner(
                mapper, TransactionOperations.withoutTransaction());
    }
}
