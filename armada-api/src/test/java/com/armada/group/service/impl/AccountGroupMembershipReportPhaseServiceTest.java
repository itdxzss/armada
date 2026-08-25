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
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
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

        when(currentSnapshotMapper.selectContextForUpdate(10L)).thenReturn(context("acc-10", null));

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
        lockOrder.verify(groupLinkMapper).selectActiveByIdsForUpdate(List.of(20L));
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
        lockOrder.verify(groupLinkMapper).selectActiveByIdsForUpdate(List.of(20L));
        lockOrder.verify(currentSnapshotPersistence).replaceVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", List.of(group));
        lockOrder.verify(metadataSyncTaskService).enqueueClassifications(
                classificationPlan.newlyPersisted(), 2_000L);
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
                10L, "15550000001", "WEB", protocolAccountId,
                2, 1, 0, 1_000L, null, lastCompleteAt);
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
                "evt", "test", true, 0);
    }
}
