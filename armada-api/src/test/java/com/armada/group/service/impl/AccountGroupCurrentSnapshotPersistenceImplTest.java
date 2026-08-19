package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.GroupId;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.MembershipExitWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantPresenceWrite;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 新模型独占新增群资格判断的快速单元回归。 */
@ExtendWith(MockitoExtension.class)
class AccountGroupCurrentSnapshotPersistenceImplTest {

    private static final long TENANT_ID = 7L;
    private static final long ACCOUNT_ID = 10L;
    private static final String GROUP_JID = "120363-new@g.us";

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
    void firstSnapshotAfterPreciseAddIsAddedButEstablishedReplayIsNot() {
        stubSnapshotContext();
        AccountGroupMembershipSnapshot current = currentGroup();
        when(mapper.selectExisting(ACCOUNT_ID, "923300000010@s.whatsapp.net", List.of(GROUP_JID)))
                .thenReturn(List.of(existing(1, "WGP2_ADD", 2_000L)))
                .thenReturn(List.of(existing(1, "GROUP_SNAPSHOT", 2_100L)));
        when(mapper.selectExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", List.of(GROUP_JID)))
                .thenReturn(List.of(existing(1, "WGP2_ADD", 2_000L)))
                .thenReturn(List.of(existing(1, "GROUP_SNAPSHOT", 2_100L)));

        var first = persistence.replaceVisibleGroups(
                ACCOUNT_ID, reportedGroups(), true, 2_100L, "snapshot-first", List.of(current));
        var replay = persistence.replaceVisibleGroups(
                ACCOUNT_ID, reportedGroups(), true, 2_200L, "snapshot-replay", List.of(current));

        assertThat(first.addedGroups()).containsExactly(current);
        assertThat(replay.addedGroups()).isEmpty();
    }

    @Test
    void staleSnapshotCannotReAddNewerExitedRelationship() {
        stubSnapshotContext();
        when(mapper.selectExisting(ACCOUNT_ID, "923300000010@s.whatsapp.net", List.of(GROUP_JID)))
                .thenReturn(List.of(existing(2, "WGP2_REMOVE", 4_000L)));
        when(mapper.selectExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", List.of(GROUP_JID)))
                .thenReturn(List.of(existing(2, "WGP2_REMOVE", 4_000L)));

        var result = persistence.replaceVisibleGroups(
                ACCOUNT_ID, reportedGroups(), false, 3_000L, "snapshot-stale",
                List.of(currentGroup()));

        assertThat(result.addedGroups()).isEmpty();
    }

    @Test
    void participantRoleObservationWritesCurrentParticipantWithoutBinding() {
        stubGroupId();
        persistence.applyParticipantObservations(List.of(new GroupParticipantObservation(
                TENANT_ID, 20L, GROUP_JID, "15550000001@s.whatsapp.net",
                "15550000001@s.whatsapp.net", "15550000001", true, true,
                WhatsappGroupMemberStateSource.ROLE_EVENT, 5_000L, "role-1")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParticipantPresenceWrite>> rows = ArgumentCaptor.forClass(List.class);
        verify(mapper).upsertParticipantFacts(rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.groupId()).isEqualTo(100L);
            assertThat(row.pnJid()).isEqualTo("15550000001@s.whatsapp.net");
            assertThat(row.presenceStatus()).isEqualTo(1);
            assertThat(row.presenceSource()).isEqualTo("WGP2_PROMOTE");
            assertThat(row.role()).isEqualTo(2);
            assertThat(row.roleObservedAt()).isEqualTo(5_000L);
        });
    }

    @Test
    void controlledAddReturnsTrueOnlyForAcceptedInGroupTransition() {
        stubSnapshotContext();
        when(mapper.selectSelfMembershipExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", GROUP_JID))
                .thenReturn(existing(2, "WGP2_REMOVE", 1_000L));

        boolean transitioned = persistence.applyControlledParticipantObservation(
                ACCOUNT_ID, GROUP_JID, true, false,
                2_000L, "event-add", "WGP2_ADD");

        assertThat(transitioned).isTrue();
        ArgumentCaptor<ParticipantPresenceWrite> row =
                ArgumentCaptor.forClass(ParticipantPresenceWrite.class);
        verify(mapper).upsertSelfBinding(eq(TENANT_ID), eq(ACCOUNT_ID), row.capture());
        assertThat(row.getValue().lastJoinedAt()).isEqualTo(2_000L);
        assertThat(row.getValue().membershipActiveSinceAt()).isEqualTo(2_000L);
    }

    @Test
    void controlledRepeatedAddDoesNotReturnAnotherTransition() {
        stubSnapshotContext();
        when(mapper.selectSelfMembershipExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", GROUP_JID))
                .thenReturn(existing(1, "WGP2_ADD", 2_000L));

        boolean transitioned = persistence.applyControlledParticipantObservation(
                ACCOUNT_ID, GROUP_JID, true, false,
                2_000L, "event-add", "WGP2_ADD");

        assertThat(transitioned).isFalse();
    }

    @Test
    void preciseSelfAddRepairsMissingMembershipActiveSince() {
        stubSnapshotContext();
        when(mapper.selectSelfMembershipExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", GROUP_JID))
                .thenReturn(existing(1, "WGP2_OBSERVATION", 2_000L));

        persistence.applySelfMembershipChanged(
                ACCOUNT_ID, GROUP_JID, AccountGroupMembershipStatus.IN_GROUP,
                3_000L, "event-add", "WGP2_ADD");

        ArgumentCaptor<ParticipantPresenceWrite> row =
                ArgumentCaptor.forClass(ParticipantPresenceWrite.class);
        verify(mapper).upsertSelfBinding(eq(TENANT_ID), eq(ACCOUNT_ID), row.capture());
        assertThat(row.getValue().membershipActiveSinceAt()).isEqualTo(3_000L);
    }

    @Test
    void preciseSelfAddDoesNotOverwriteExistingMembershipActiveSince() {
        stubSnapshotContext();
        when(mapper.selectSelfMembershipExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", GROUP_JID))
                .thenReturn(new Existing(
                        GROUP_JID, 100L, 200L, 1, "WGP2_ADD", 2_000L,
                        300L, 0, null, 2_000L, null));

        persistence.applySelfMembershipChanged(
                ACCOUNT_ID, GROUP_JID, AccountGroupMembershipStatus.IN_GROUP,
                3_000L, "event-add-replay", "WGP2_ADD");

        ArgumentCaptor<ParticipantPresenceWrite> row =
                ArgumentCaptor.forClass(ParticipantPresenceWrite.class);
        verify(mapper).upsertSelfBinding(eq(TENANT_ID), eq(ACCOUNT_ID), row.capture());
        assertThat(row.getValue().membershipActiveSinceAt()).isNull();
    }

    @Test
    void nonAddObservationDoesNotInventMissingMembershipActiveSince() {
        stubSnapshotContext();
        when(mapper.selectSelfMembershipExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", GROUP_JID))
                .thenReturn(existing(1, "WGP2_OBSERVATION", 2_000L));

        persistence.applySelfMembershipChanged(
                ACCOUNT_ID, GROUP_JID, AccountGroupMembershipStatus.IN_GROUP,
                3_000L, "event-observation", "GROUP_MEMBER_QUERY");

        ArgumentCaptor<ParticipantPresenceWrite> row =
                ArgumentCaptor.forClass(ParticipantPresenceWrite.class);
        verify(mapper).upsertSelfBinding(eq(TENANT_ID), eq(ACCOUNT_ID), row.capture());
        assertThat(row.getValue().membershipActiveSinceAt()).isNull();
    }

    @Test
    void delayedPreciseAddRepairsActiveSinceWithoutReplacingNewerInGroupObservation() {
        stubSnapshotContext();
        when(mapper.selectSelfMembershipExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", GROUP_JID))
                .thenReturn(existing(1, "WGP2_PROMOTE", 4_000L));

        boolean transitioned = persistence.applyControlledParticipantObservation(
                ACCOUNT_ID, GROUP_JID, true, false,
                3_000L, "delayed-add", "WGP2_ADD");

        assertThat(transitioned).isTrue();
        ArgumentCaptor<ParticipantPresenceWrite> row =
                ArgumentCaptor.forClass(ParticipantPresenceWrite.class);
        verify(mapper).upsertSelfBinding(eq(TENANT_ID), eq(ACCOUNT_ID), row.capture());
        assertThat(row.getValue().membershipActiveSinceAt()).isEqualTo(3_000L);
        assertThat(row.getValue().occurredAt()).isEqualTo(3_000L);
    }

    @Test
    void delayedPreciseAddCannotRepairAfterNewerExit() {
        stubSnapshotContext();
        when(mapper.selectSelfMembershipExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", GROUP_JID))
                .thenReturn(existing(2, "WGP2_REMOVE", 4_000L));

        boolean transitioned = persistence.applyControlledParticipantObservation(
                ACCOUNT_ID, GROUP_JID, true, false,
                3_000L, "delayed-add", "WGP2_ADD");

        assertThat(transitioned).isFalse();
        ArgumentCaptor<ParticipantPresenceWrite> row =
                ArgumentCaptor.forClass(ParticipantPresenceWrite.class);
        verify(mapper).upsertSelfBinding(eq(TENANT_ID), eq(ACCOUNT_ID), row.capture());
        assertThat(row.getValue().membershipActiveSinceAt()).isNull();
    }

    @Test
    void acceptedExitClearsCurrentMembershipCycleBeforeBindingUpsert() {
        stubSnapshotContext();
        when(mapper.selectSelfMembershipExistingForUpdate(
                TENANT_ID, ACCOUNT_ID, "923300000010@s.whatsapp.net", GROUP_JID))
                .thenReturn(existing(1, "WGP2_ADD", 1_000L));

        persistence.applyControlledParticipantObservation(
                ACCOUNT_ID, GROUP_JID, false, false,
                2_000L, "remove-event", "WGP2_REMOVE");

        InOrder writes = inOrder(mapper);
        writes.verify(mapper).upsertParticipantFacts(org.mockito.ArgumentMatchers.anyList());
        ArgumentCaptor<MembershipExitWrite> exit =
                ArgumentCaptor.forClass(MembershipExitWrite.class);
        writes.verify(mapper).clearMembershipActiveSinceForAcceptedExit(
                eq(TENANT_ID), exit.capture());
        assertThat(exit.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(exit.getValue().groupIds()).containsExactly(100L);
        assertThat(exit.getValue().presenceSource()).isEqualTo("WGP2_REMOVE");
        assertThat(exit.getValue().observedAt()).isEqualTo(2_000L);
        writes.verify(mapper).upsertSelfBinding(
                eq(TENANT_ID), eq(ACCOUNT_ID),
                org.mockito.ArgumentMatchers.any(ParticipantPresenceWrite.class));
    }

    private static Existing existing(int presenceStatus, String source, long observedAt) {
        return new Existing(
                GROUP_JID, 100L, 200L, presenceStatus, source, observedAt,
                300L, 0, null,
                "WGP2_ADD".equals(source) ? observedAt : null,
                null);
    }

    private void stubGroupId() {
        when(mapper.selectGroupIds(TENANT_ID, List.of(GROUP_JID)))
                .thenReturn(List.of(new GroupId(GROUP_JID, 100L)));
    }

    private void stubSnapshotContext() {
        when(mapper.selectContext(ACCOUNT_ID)).thenReturn(new Context(
                ACCOUNT_ID,
                "923300000010",
                "WEB",
                "acc-10",
                AccountGroupBaselineStateCode.DISABLED,
                0,
                0,
                null,
                null));
    }

    private static List<AccountGroupsReportedEvent.Group> reportedGroups() {
        return List.of(new AccountGroupsReportedEvent.Group(
                GROUP_JID, "新群", 10, null, null, false, false, null));
    }

    private static AccountGroupMembershipSnapshot currentGroup() {
        return new AccountGroupMembershipSnapshot(
                400L, GROUP_JID, "新群", "wa://group/" + GROUP_JID, false);
    }
}
