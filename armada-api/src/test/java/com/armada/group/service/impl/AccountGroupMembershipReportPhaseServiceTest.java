package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.model.vo.AccountGroupCompatibilitySnapshot;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.group.model.vo.GroupPostControlClassificationCandidate;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.security.DataScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 账号群回报两阶段内部编排单测。 */
@ExtendWith(MockitoExtension.class)
class AccountGroupMembershipReportPhaseServiceTest {

    @Mock private AccountGroupMembershipSnapshotService snapshotService;
    @Mock private GroupClassificationService classificationService;
    @Mock private AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;
    @Mock private MarketingNewGroupImmediateSendService immediateSendService;
    @Mock private GroupMetadataSyncTaskService metadataSyncTaskService;
    @Mock private AccountGroupCurrentSnapshotMapper currentSnapshotMapper;
    @Mock private GroupLinkMapper groupLinkMapper;

    @Test
    void pendingBaselineClassifiesResolvedStableHandleInsteadOfRegisteringAgain() {
        AccountGroupsReportedEvent event = event();
        AccountGroupMembershipSnapshot group = snapshot();
        when(snapshotService.prepareVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", "test", ProtocolBackend.WEB))
                .thenReturn(new AccountGroupCompatibilitySnapshot(
                        List.of(group), new GroupClassificationPlan(
                                Map.of(20L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED),
                                Map.of(20L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED))));
        GroupClassificationPlan baselinePlan = new GroupClassificationPlan(
                Map.of(20L, GroupMetadataSyncTrigger.BASELINE_CAPTURED),
                Map.of(20L, GroupMetadataSyncTrigger.BASELINE_CAPTURED));
        when(classificationService.stageHistoricalBaseline(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(ProtocolBackend.WEB), anyLong()))
                .thenReturn(baselinePlan);

        when(currentSnapshotMapper.selectContextForUpdate(10L)).thenReturn(pendingContext());

        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult actual =
                service().prepareCompatibility(
                event, ProtocolBackend.WEB, true, true, 2_000L);

        assertThat(actual.accepted()).isTrue();
        assertThat(actual.groups()).containsExactly(group);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupClassificationCandidate>> candidates =
                ArgumentCaptor.forClass(List.class);
        verify(classificationService).stageHistoricalBaseline(
                candidates.capture(), org.mockito.ArgumentMatchers.eq(ProtocolBackend.WEB), anyLong());
        assertThat(candidates.getValue()).containsExactly(
                new GroupClassificationCandidate(20L, "120363001@g.us", "群一"));
        assertThat(actual.classificationPlan()).isEqualTo(baselinePlan);
    }

    @Test
    void incompletePendingBaselineDoesNotClassifyAnyGroup() {
        AccountGroupsReportedEvent event = event();
        when(snapshotService.prepareVisibleGroups(
                10L, event.groups(), false, 2_000L, "evt", "test", ProtocolBackend.WEB))
                .thenReturn(new AccountGroupCompatibilitySnapshot(
                        List.of(snapshot()), GroupClassificationPlan.empty()));
        when(currentSnapshotMapper.selectContextForUpdate(10L))
                .thenReturn(pendingContext());

        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult actual =
                service().prepareCompatibility(
                        event, ProtocolBackend.WEB, true, false, 2_000L);

        assertThat(actual.accepted()).isTrue();
        verify(classificationService, never()).stageHistoricalBaseline(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(), anyLong());
        verify(classificationService, never()).stagePostControlEvidence(
                org.mockito.ArgumentMatchers.anyList(), anyLong());
    }

    @Test
    void stalePendingHintCannotReclassifyAfterAnotherSnapshotCapturedBaseline() {
        AccountGroupsReportedEvent event = event();
        when(snapshotService.prepareVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", "test", ProtocolBackend.WEB))
                .thenReturn(new AccountGroupCompatibilitySnapshot(
                        List.of(snapshot()), GroupClassificationPlan.empty()));
        when(currentSnapshotMapper.selectContextForUpdate(10L))
                .thenReturn(context("acc-10", 1_000L));

        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult actual =
                service().prepareCompatibility(
                        event, ProtocolBackend.WEB, true, true, 2_000L);

        assertThat(actual.accepted()).isTrue();
        verify(classificationService, never()).stageHistoricalBaseline(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void completePendingBaselineSplitsHistoricalAndPostCutoffEvidence() {
        AccountGroupsReportedEvent.Group historical = new AccountGroupsReportedEvent.Group(
                "120363001@g.us", "历史群", 20, null, null, true, false, null);
        AccountGroupsReportedEvent.Group postControl = new AccountGroupsReportedEvent.Group(
                "120363002@g.us", "新群", 10, null, null, false, false, null,
                null, null, null, null, false, null, null,
                2_500L, "wgp2-self-add-1");
        AccountGroupsReportedEvent event = new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 3_000L, List.of(historical, postControl),
                "evt-race", "test", "command-1", "snapshot-1",
                2_000L, 2_000L, true, 0);
        AccountGroupMembershipSnapshot historicalSnapshot = new AccountGroupMembershipSnapshot(
                20L, historical.groupJid(), historical.subject(), "wa://group/" + historical.groupJid(), true);
        AccountGroupMembershipSnapshot postSnapshot = new AccountGroupMembershipSnapshot(
                21L, postControl.groupJid(), postControl.subject(), "wa://group/" + postControl.groupJid(), false);
        when(snapshotService.prepareVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt-race", "test", ProtocolBackend.WEB))
                .thenReturn(new AccountGroupCompatibilitySnapshot(
                        List.of(historicalSnapshot, postSnapshot), GroupClassificationPlan.empty()));
        when(currentSnapshotMapper.selectContextForUpdate(10L))
                .thenReturn(pendingContext());
        GroupClassificationPlan postPlan = new GroupClassificationPlan(
                Map.of(21L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED),
                Map.of(21L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED));
        GroupClassificationPlan historicalPlan = new GroupClassificationPlan(
                Map.of(20L, GroupMetadataSyncTrigger.BASELINE_CAPTURED),
                Map.of(20L, GroupMetadataSyncTrigger.BASELINE_CAPTURED));
        when(classificationService.stagePostControlEvidence(
                org.mockito.ArgumentMatchers.anyList(), anyLong())).thenReturn(postPlan);
        when(classificationService.stageHistoricalBaseline(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(ProtocolBackend.WEB), anyLong()))
                .thenReturn(historicalPlan);

        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult actual =
                service().prepareCompatibility(
                        event, ProtocolBackend.WEB, true, true, 2_000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupPostControlClassificationCandidate>> postCandidates =
                ArgumentCaptor.forClass(List.class);
        verify(classificationService).stagePostControlEvidence(postCandidates.capture(), anyLong());
        assertThat(postCandidates.getValue()).containsExactly(
                new GroupPostControlClassificationCandidate(
                        21L, postControl.groupJid(), postControl.subject(), 2_500L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupClassificationCandidate>> historicalCandidates =
                ArgumentCaptor.forClass(List.class);
        verify(classificationService).stageHistoricalBaseline(
                historicalCandidates.capture(),
                org.mockito.ArgumentMatchers.eq(ProtocolBackend.WEB), anyLong());
        assertThat(historicalCandidates.getValue()).containsExactly(
                new GroupClassificationCandidate(
                        20L, historical.groupJid(), historical.subject()));
        assertThat(actual.classificationPlan().desired()).containsOnlyKeys(20L, 21L);
    }

    @Test
    void currentFactsAndMarketingAreAppliedTogetherOnlyAfterBaselineCaptured() {
        AccountGroupsReportedEvent event = event();
        AccountGroupMembershipSnapshot group = snapshot();
        AccountGroupMembershipChangeSet changes =
                new AccountGroupMembershipChangeSet(List.of(group), List.of(group));
        when(currentSnapshotPersistence.replaceVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", List.of(group)))
                .thenReturn(changes);
        when(currentSnapshotMapper.selectContextForUpdate(10L))
                .thenReturn(context("acc-10", 1_000L));

        GroupClassificationPlan classificationPlan = new GroupClassificationPlan(
                Map.of(20L, GroupMetadataSyncTrigger.BASELINE_CAPTURED),
                Map.of(20L, GroupMetadataSyncTrigger.BASELINE_CAPTURED));
        assertThat(service().applyCurrentSnapshot(
                event, true, 2_000L, List.of(group), classificationPlan, false)).isSameAs(changes);

        InOrder lockOrder = inOrder(
                groupLinkMapper, currentSnapshotPersistence, metadataSyncTaskService);
        lockOrder.verify(groupLinkMapper).selectActiveByIdsForUpdate(
                org.mockito.ArgumentMatchers.eq(List.of(20L)),
                org.mockito.ArgumentMatchers.any(DataScope.class));
        lockOrder.verify(currentSnapshotPersistence).replaceVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", List.of(group));
        lockOrder.verify(metadataSyncTaskService).enqueueClassifications(
                classificationPlan.newlyPersisted(), 2_000L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketingNewGroupDTO>> marketingGroups =
                ArgumentCaptor.forClass(List.class);
        verify(immediateSendService).enqueueNewGroups(
                org.mockito.ArgumentMatchers.eq(10L), marketingGroups.capture(),
                org.mockito.ArgumentMatchers.eq(2_000L));
        assertThat(marketingGroups.getValue()).containsExactly(
                new MarketingNewGroupDTO(20L, "120363001@g.us", "群一"));
    }

    @Test
    void pendingBaselineDoesNotEnqueueNewGroupMarketing() {
        AccountGroupsReportedEvent event = event();
        AccountGroupMembershipSnapshot group = snapshot();
        when(currentSnapshotPersistence.replaceVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", List.of(group)))
                .thenReturn(new AccountGroupMembershipChangeSet(List.of(group), List.of(group)));
        when(currentSnapshotMapper.selectContextForUpdate(10L))
                .thenReturn(context("acc-10", 1_000L));

        service().applyCurrentSnapshot(
                event, true, 2_000L, List.of(group), GroupClassificationPlan.empty(), true);

        verify(immediateSendService, never()).enqueueNewGroups(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), anyLong());
    }

    @Test
    void manualRefreshLocksAccountAndGroupBeforeCurrentFactsAndClassificationTask() {
        AccountGroupsReportedEvent event = event();
        AccountGroupMembershipSnapshot group = snapshot();
        GroupClassificationPlan classificationPlan = new GroupClassificationPlan(
                Map.of(20L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED),
                Map.of(20L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED));
        AccountGroupCompatibilitySnapshot prepared = new AccountGroupCompatibilitySnapshot(
                List.of(group), classificationPlan);
        AccountGroupMembershipChangeSet changes =
                new AccountGroupMembershipChangeSet(List.of(group), List.of(group));
        when(currentSnapshotMapper.selectContextForUpdate(10L))
                .thenReturn(context("acc-10", 1_000L));
        when(currentSnapshotPersistence.replaceVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", List.of(group)))
                .thenReturn(changes);

        assertThat(service().applyManualCurrentSnapshot(
                10L, event.groups(), 2_000L, "evt", prepared)).isSameAs(changes);

        InOrder lockOrder = inOrder(
                currentSnapshotMapper, groupLinkMapper,
                currentSnapshotPersistence, metadataSyncTaskService);
        lockOrder.verify(currentSnapshotMapper).selectContextForUpdate(10L);
        lockOrder.verify(groupLinkMapper).selectActiveByIdsForUpdate(
                org.mockito.ArgumentMatchers.eq(List.of(20L)),
                org.mockito.ArgumentMatchers.any(DataScope.class));
        lockOrder.verify(currentSnapshotPersistence).replaceVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", List.of(group));
        lockOrder.verify(metadataSyncTaskService).enqueueClassifications(
                classificationPlan.newlyPersisted(), 2_000L);
        verifyNoInteractions(immediateSendService);
    }

    @Test
    void manualRefreshCapturesPendingBaselineAsHistoricalBeforeWritingCurrentFacts() {
        AccountGroupsReportedEvent event = event();
        AccountGroupMembershipSnapshot group = snapshot();
        AccountGroupCompatibilitySnapshot prepared = new AccountGroupCompatibilitySnapshot(
                List.of(group), GroupClassificationPlan.empty());
        GroupClassificationPlan historicalPlan = new GroupClassificationPlan(
                Map.of(20L, GroupMetadataSyncTrigger.BASELINE_CAPTURED),
                Map.of(20L, GroupMetadataSyncTrigger.BASELINE_CAPTURED));
        AccountGroupMembershipChangeSet changes =
                new AccountGroupMembershipChangeSet(List.of(group), List.of());
        when(currentSnapshotMapper.selectContextForUpdate(10L)).thenReturn(pendingContext());
        when(classificationService.stageHistoricalBaseline(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(ProtocolBackend.WEB),
                org.mockito.ArgumentMatchers.eq(2_000L))).thenReturn(historicalPlan);
        when(currentSnapshotPersistence.replaceVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", List.of(group)))
                .thenReturn(changes);

        assertThat(service().applyManualCurrentSnapshot(
                10L, event.groups(), 2_000L, "evt", prepared)).isSameAs(changes);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupClassificationCandidate>> candidates =
                ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(
                currentSnapshotMapper, groupLinkMapper, classificationService,
                currentSnapshotPersistence, metadataSyncTaskService);
        order.verify(currentSnapshotMapper).selectContextForUpdate(10L);
        order.verify(groupLinkMapper).selectActiveByIdsForUpdate(
                org.mockito.ArgumentMatchers.eq(List.of(20L)),
                org.mockito.ArgumentMatchers.any(DataScope.class));
        order.verify(classificationService).stageHistoricalBaseline(
                candidates.capture(), org.mockito.ArgumentMatchers.eq(ProtocolBackend.WEB),
                org.mockito.ArgumentMatchers.eq(2_000L));
        order.verify(currentSnapshotPersistence).replaceVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", List.of(group));
        order.verify(metadataSyncTaskService).enqueueClassifications(
                historicalPlan.newlyPersisted(), 2_000L);
        assertThat(candidates.getValue()).containsExactly(
                new GroupClassificationCandidate(20L, "120363001@g.us", "群一"));
        verifyNoInteractions(immediateSendService);
    }

    @Test
    void staleCompleteSnapshotStopsBeforeCompatibilityWrites() {
        AccountGroupsReportedEvent event = event();
        when(currentSnapshotMapper.selectContextForUpdate(10L))
                .thenReturn(context("acc-10", 3_000L));

        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult result =
                service().prepareCompatibility(
                        event, ProtocolBackend.WEB, false, true, 2_000L);

        assertThat(result.accepted()).isFalse();
        assertThat(result.groups()).isEmpty();
        verifyNoInteractions(snapshotService, classificationService);
    }

    @Test
    void staleIncompleteSnapshotStopsBeforeCompatibilityWrites() {
        AccountGroupsReportedEvent event = event();
        when(currentSnapshotMapper.selectContextForUpdate(10L))
                .thenReturn(context("acc-10", 3_000L));

        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult result =
                service().prepareCompatibility(
                        event, ProtocolBackend.WEB, false, false, 2_000L);

        assertThat(result.accepted()).isFalse();
        verifyNoInteractions(snapshotService, classificationService);
    }

    @Test
    void reboundAccountStopsPhaseTwoBeforeCurrentFactsAndMarketing() {
        AccountGroupsReportedEvent event = event();
        when(currentSnapshotMapper.selectContextForUpdate(10L))
                .thenReturn(context("replacement-account", 1_000L));

        AccountGroupMembershipChangeSet result = service().applyCurrentSnapshot(
                event, true, 2_000L, List.of(snapshot()),
                GroupClassificationPlan.empty(), false);

        assertThat(result.currentGroups()).isEmpty();
        assertThat(result.addedGroups()).isEmpty();
        verifyNoInteractions(currentSnapshotPersistence, immediateSendService, metadataSyncTaskService);
    }

    private AccountGroupMembershipReportPhaseService service() {
        return new AccountGroupMembershipReportPhaseService(
                currentSnapshotMapper,
                groupLinkMapper,
                snapshotService, classificationService,
                currentSnapshotPersistence, immediateSendService, metadataSyncTaskService);
    }

    private static Context context(String protocolAccountId, Long lastCompleteAt) {
        return new Context(
                10L, 501L, "15550000001", "WEB", protocolAccountId,
                2, 1, 0, 1_000L, null, lastCompleteAt);
    }

    private static Context pendingContext() {
        return new Context(
                10L, 501L, "15550000001", "WEB", "acc-10",
                1, 0, null, null, null, null);
    }

    private static AccountGroupMembershipSnapshot snapshot() {
        return new AccountGroupMembershipSnapshot(
                20L, "120363001@g.us", "群一", "wa://group/120363001@g.us", true);
    }

    private static AccountGroupsReportedEvent event() {
        return new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 2_000L,
                List.of(new AccountGroupsReportedEvent.Group(
                        "120363001@g.us", "群一", 20, null, null, true, false, null)),
                "evt", "test", null, null, null, null, true, 0);
    }
}
