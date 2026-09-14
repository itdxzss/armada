package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ControlledExisting;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ControlledObservation;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ControlledWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.GroupId;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantPresenceWrite;
import com.armada.group.model.dto.ControlledAccountGroupTransition;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 同群批量成员观察的集合调用边界及既有进群周期判定回归。 */
@ExtendWith(MockitoExtension.class)
class AccountGroupControlledBatchPersistenceTest {

    private static final long TENANT_ID = 7L;
    private static final long GROUP_ID = 100L;
    private static final String GROUP_JID = "120363-batch@g.us";

    @Mock
    private AccountGroupCurrentSnapshotMapper mapper;

    private AccountGroupCurrentSnapshotPersistenceImpl persistence;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        persistence = new AccountGroupCurrentSnapshotPersistenceImpl(mapper);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void fifteenAccountsKeepIndividualRolesWithOneGroupLockAndOneWritePerTable() {
        List<Long> accountIds = accountIds(15);
        stubBatch(accountIds, currentMembers(accountIds));
        List<ControlledObservation> observations = new ArrayList<>(snapshots(accountIds));
        Collections.reverse(observations);

        assertThat(persistence.applyControlledParticipantObservations(GROUP_JID, observations))
                .isEmpty();

        verifySharedGroupReads(accountIds);
        verify(mapper).selectControlledExistingAfterGroupLock(
                eq(TENANT_ID), eq(GROUP_ID), anyList());
        verify(mapper).selectParticipantIdentityRowsForUpdate(eq(TENANT_ID), anyList());
        ArgumentCaptor<List<ParticipantPresenceWrite>> members = listCaptor();
        verify(mapper).upsertParticipantFacts(members.capture());
        ArgumentCaptor<List<ControlledWrite>> bindings = listCaptor();
        verify(mapper).upsertControlledBindings(eq(TENANT_ID), bindings.capture());
        assertThat(bindings.getValue()).extracting(ControlledWrite::accountId)
                .containsExactlyElementsOf(accountIds);
        assertThat(members.getValue()).containsExactlyElementsOf(bindings.getValue().stream()
                .map(ControlledWrite::row).toList());
        assertThat(bindings.getValue()).allSatisfy(write -> {
            ParticipantPresenceWrite row = write.row();
            assertThat(row.groupId()).isEqualTo(GROUP_ID);
            assertThat(row.groupJid()).isEqualTo(GROUP_JID);
            assertThat(row.pnJid()).isEqualTo(phone(write.accountId()) + "@s.whatsapp.net");
            assertThat(row.role()).isEqualTo(write.accountId() % 2 == 0 ? 2 : 1);
            assertThat(row.eventId()).isEqualTo("snapshot-" + write.accountId());
            assertThat(row.roleSource()).isEqualTo("GROUP_SNAPSHOT");
            assertThat(row.membershipActiveSinceAt()).isNull();
        });
        InOrder order = inOrder(mapper);
        order.verify(mapper).selectGroupIdsByIdsForUpdate(TENANT_ID, List.of(GROUP_ID));
        order.verify(mapper).selectControlledExistingAfterGroupLock(
                eq(TENANT_ID), eq(GROUP_ID), anyList());
        order.verify(mapper).upsertParticipantFacts(anyList());
        order.verify(mapper).upsertControlledBindings(eq(TENANT_ID), anyList());
        // 全量交互断言同时禁止悄悄退回逐账号 context/current/binding 路径。
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void twoHundredAndOneAccountsAddOnlyASecondBoundedParticipantAndBindingBatch() {
        List<Long> accountIds = accountIds(201);
        stubBatch(accountIds, currentMembers(accountIds));

        assertThat(persistence.applyControlledParticipantObservations(
                GROUP_JID, snapshots(accountIds))).isEmpty();

        verifySharedGroupReads(accountIds);
        ArgumentCaptor<List<ControlledWrite>> reads = listCaptor();
        verify(mapper, times(2)).selectControlledExistingAfterGroupLock(
                eq(TENANT_ID), eq(GROUP_ID), reads.capture());
        ArgumentCaptor<List<ParticipantPresenceWrite>> identities = listCaptor();
        verify(mapper, times(2)).selectParticipantIdentityRowsForUpdate(
                eq(TENANT_ID), identities.capture());
        ArgumentCaptor<List<ParticipantPresenceWrite>> members = listCaptor();
        verify(mapper, times(2)).upsertParticipantFacts(members.capture());
        ArgumentCaptor<List<ControlledWrite>> bindings = listCaptor();
        verify(mapper, times(2)).upsertControlledBindings(eq(TENANT_ID), bindings.capture());
        assertThat(reads.getAllValues()).extracting(List::size).containsExactly(200, 1);
        assertThat(identities.getAllValues()).extracting(List::size).containsExactly(200, 1);
        assertThat(members.getAllValues()).extracting(List::size).containsExactly(200, 1);
        assertThat(bindings.getAllValues()).extracting(List::size).containsExactly(200, 1);
        assertThat(bindings.getAllValues().stream().flatMap(List::stream)
                .map(ControlledWrite::accountId).toList()).containsExactlyElementsOf(accountIds);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void mixedDelayedFactsKeepExistingTransitionRulesAndPassGuardedExitsBeforeBindings() {
        List<Long> accountIds = accountIds(7);
        stubBatch(accountIds, List.of(
                existing(1L, 2, "WGP2_REMOVE", 4_000L, null),
                existing(2L, 1, "WGP2_PROMOTE", 4_000L, null),
                existing(3L, 2, "WGP2_REMOVE", 4_000L, null),
                existing(4L, 1, "WGP2_ADD", 2_000L, 2_000L),
                existing(5L, 2, "WGP2_REMOVE", 1_000L, null),
                existing(6L, 1, "WGP2_ADD", 4_000L, 4_000L),
                existing(7L, 1, "WGP2_PROMOTE", 4_000L, null)));
        List<ControlledObservation> observations = List.of(
                observation(1L, true, 3_000L, "GROUP_SNAPSHOT"),
                observation(2L, true, 3_000L, "WGP2_ADD"),
                observation(3L, true, 3_000L, "WGP2_ADD"),
                observation(4L, true, 2_000L, "WGP2_ADD"),
                observation(5L, true, 2_000L, "WGP2_ADD"),
                observation(6L, false, 3_000L, "WGP2_REMOVE"),
                observation(7L, true, 3_000L, "GROUP_SNAPSHOT"));

        assertThat(persistence.applyControlledParticipantObservations(GROUP_JID, observations))
                .containsExactly(new ControlledAccountGroupTransition(2L, GROUP_JID),
                        new ControlledAccountGroupTransition(5L, GROUP_JID));

        ArgumentCaptor<List<ControlledWrite>> bindings = listCaptor();
        ArgumentCaptor<List<ControlledWrite>> exits = listCaptor();
        InOrder order = inOrder(mapper);
        order.verify(mapper).upsertParticipantFacts(anyList());
        order.verify(mapper).clearControlledMembershipActiveSinceForAcceptedExits(
                eq(TENANT_ID), exits.capture());
        order.verify(mapper).upsertControlledBindings(eq(TENANT_ID), bindings.capture());
        Map<Long, ParticipantPresenceWrite> rows = bindings.getValue().stream()
                .collect(Collectors.toMap(ControlledWrite::accountId, ControlledWrite::row));
        assertThat(rows.get(2L).membershipActiveSinceAt()).isEqualTo(3_000L);
        assertThat(rows.get(2L).lastJoinedAt()).isEqualTo(3_000L);
        assertThat(rows.get(5L).membershipActiveSinceAt()).isEqualTo(2_000L);
        assertThat(List.of(1L, 3L, 4L, 6L, 7L)).allSatisfy(accountId ->
                assertThat(rows.get(accountId).membershipActiveSinceAt()).isNull());
        // 旧事实仍交给既有 SQL 按时序裁决，不能改写其事件时间来强行取得更新资格。
        assertThat(observations).allSatisfy(observation -> {
            ParticipantPresenceWrite row = rows.get(observation.accountId());
            assertThat(row.occurredAt()).isEqualTo(observation.observedAt());
            assertThat(row.presenceSource()).isEqualTo(observation.source());
            assertThat(row.eventId()).isEqualTo(observation.eventId());
        });
        assertThat(exits.getValue()).singleElement().satisfies(exit -> {
            assertThat(exit.accountId()).isEqualTo(6L);
            assertThat(exit.row().presenceStatus()).isEqualTo(2);
            assertThat(exit.row().occurredAt()).isEqualTo(3_000L);
            assertThat(exit.row().role()).isNull();
        });
    }

    @Test
    void emptyObservationsNeedNoTenantOrDatabaseCalls() {
        TenantContext.clear();

        assertThat(persistence.applyControlledParticipantObservations(GROUP_JID, List.of()))
                .isEmpty();
        assertThat(persistence.applyControlledParticipantObservations(GROUP_JID, null))
                .isEmpty();

        verifyNoInteractions(mapper);
    }

    @Test
    void missingAccountInTenantFilteredContextsFailsBeforeGroupLockOrAnyWrite() {
        List<Long> accountIds = List.of(1L, 2L);
        when(mapper.selectContexts(TENANT_ID, accountIds)).thenReturn(List.of(context(1L)));

        assertThatThrownBy(() -> persistence.applyControlledParticipantObservations(
                GROUP_JID, snapshots(accountIds)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("找不到活跃账号");

        verify(mapper).selectContexts(TENANT_ID, accountIds);
        verifyNoMoreInteractions(mapper);
    }

    private void stubBatch(List<Long> accountIds, List<ControlledExisting> existingRows) {
        when(mapper.selectContexts(TENANT_ID, accountIds))
                .thenReturn(accountIds.stream().map(AccountGroupControlledBatchPersistenceTest::context)
                        .toList());
        when(mapper.selectGroupIdsWithoutLock(TENANT_ID, List.of(GROUP_JID)))
                .thenReturn(List.of(new GroupId(GROUP_JID, GROUP_ID)));
        when(mapper.selectGroupIdsByIdsForUpdate(TENANT_ID, List.of(GROUP_ID)))
                .thenReturn(List.of(new GroupId(GROUP_JID, GROUP_ID)));
        Map<Long, ControlledExisting> existing = existingRows.stream().collect(
                Collectors.toMap(ControlledExisting::accountId, Function.identity()));
        when(mapper.selectControlledExistingAfterGroupLock(
                eq(TENANT_ID), eq(GROUP_ID), anyList())).thenAnswer(invocation -> {
                    List<ControlledWrite> rows = invocation.getArgument(2);
                    return rows.stream().map(row -> existing.get(row.accountId())).toList();
                });
    }

    private void verifySharedGroupReads(List<Long> accountIds) {
        verify(mapper).selectContexts(TENANT_ID, accountIds);
        verify(mapper).selectUnboundLegacyGroupHandlesWithoutLock(TENANT_ID, List.of(GROUP_JID));
        verify(mapper).selectGroupIdsWithoutLock(TENANT_ID, List.of(GROUP_JID));
        verify(mapper).selectGroupIdsByIdsForUpdate(TENANT_ID, List.of(GROUP_ID));
    }

    private static List<Long> accountIds(int count) {
        return LongStream.rangeClosed(1, count).boxed().toList();
    }

    private static List<ControlledObservation> snapshots(List<Long> accountIds) {
        return accountIds.stream().map(accountId -> new ControlledObservation(
                accountId, true, accountId % 2 == 0, 2_000L,
                "snapshot-" + accountId, "GROUP_SNAPSHOT")).toList();
    }

    private static ControlledObservation observation(
            long accountId, boolean inGroup, long observedAt, String source) {
        return new ControlledObservation(accountId, inGroup, false, observedAt,
                "event-" + accountId, source);
    }

    private static List<ControlledExisting> currentMembers(List<Long> accountIds) {
        return accountIds.stream().map(accountId ->
                existing(accountId, 1, "WGP2_ADD", 1_000L, 1_000L)).toList();
    }

    private static ControlledExisting existing(
            long accountId, int presence, String source, long observedAt, Long activeSince) {
        return new ControlledExisting(accountId, GROUP_JID, GROUP_ID,
                1_000L + accountId, presence, source, observedAt,
                2_000L + accountId, null, null, activeSince, null);
    }

    private static Context context(Long accountId) {
        return new Context(accountId, phone(accountId), "WEB", "acc-" + accountId,
                AccountGroupBaselineStateCode.DISABLED, 0, 0, null, null, null);
    }

    private static String phone(Long accountId) {
        return Long.toString(923300000000L + accountId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<List<T>> listCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
