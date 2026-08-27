package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 账号群回报当前模型编排单测。 */
@ExtendWith(MockitoExtension.class)
class AccountGroupMembershipReportServiceImplTest {

    @Mock private AccountGroupCurrentSnapshotMapper mapper;
    @Mock private AccountGroupMembershipReportPhaseService phaseService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void capturedBaselineWritesCurrentSnapshotAndEnqueuesAddedGroups() {
        AccountGroupsReportedEvent event = event();
        AccountGroupMembershipSnapshot group = new AccountGroupMembershipSnapshot(
                20L, "120363001@g.us", "群一", "wa://group/120363001@g.us", true);
        when(mapper.selectContext(10L)).thenReturn(context(2));
        when(phaseService.prepareCompatibility(
                event, com.armada.platform.protocol.model.enums.ProtocolBackend.WEB,
                false, true, 2_000L)).thenReturn(
                        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult.accepted(
                                List.of(group), GroupClassificationPlan.empty()));
        when(phaseService.applyCurrentSnapshot(
                event, true, 2_000L, List.of(group), GroupClassificationPlan.empty(), false))
                .thenReturn(new AccountGroupMembershipChangeSet(List.of(group), List.of(group)));

        service().applyGroupsReported(event);

        verify(phaseService).applyCurrentSnapshot(
                event, true, 2_000L, List.of(group), GroupClassificationPlan.empty(), false);
    }

    @Test
    void lateCompleteReplayStopsBeforePhaseOne() {
        AccountGroupsReportedEvent event = event();
        when(mapper.selectContext(10L)).thenReturn(context(2, 3_000L));

        service().applyGroupsReported(event);

        verifyNoInteractions(phaseService);
    }

    @Test
    void lateIncompleteReplayAlsoStopsBeforePhaseOne() {
        AccountGroupsReportedEvent event = new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 2_000L,
                event().groups(), "evt-incomplete", "test",
                null, null, null, null, false, 1);
        when(mapper.selectContext(10L)).thenReturn(context(2, 3_000L));

        service().applyGroupsReported(event);

        verifyNoInteractions(phaseService);
    }

    @Test
    void missingReportedAtIsRejectedBeforeWatermarkAndPhaseWrites() {
        AccountGroupsReportedEvent event = new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", null,
                event().groups(), "evt-missing-time", "test",
                null, null, null, null, true, 0);

        assertThatThrownBy(() -> service().applyGroupsReported(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号群列表事件缺少 reportedAt");

        verifyNoInteractions(mapper, phaseService);
    }

    @Test
    void legacyWebSnapshotWithoutQueryBoundaryFailsClosedAsIncomplete() {
        AccountGroupsReportedEvent event = new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 2_000L,
                event().groups(), "evt-legacy", "test",
                null, null, null, null, true, 0);
        when(mapper.selectContext(10L)).thenReturn(context(1));
        when(phaseService.prepareCompatibility(
                event, com.armada.platform.protocol.model.enums.ProtocolBackend.WEB,
                true, false, 2_000L)).thenReturn(
                        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult.accepted(
                                List.of(), GroupClassificationPlan.empty()));
        when(phaseService.applyCurrentSnapshot(
                event, false, 2_000L, List.of(), GroupClassificationPlan.empty(), true))
                .thenReturn(new AccountGroupMembershipChangeSet(List.of(), List.of()));

        service().applyGroupsReported(event);

        verify(phaseService).applyCurrentSnapshot(
                event, false, 2_000L, List.of(), GroupClassificationPlan.empty(), true);
    }

    @Test
    void postControlObservationBeforeExactCutoffIsRejectedEvenWithinSameSecond() {
        AccountGroupsReportedEvent.Group group = new AccountGroupsReportedEvent.Group(
                "120363001@g.us", "群一", 20, null, null, true, false, null,
                null, null, null, null, false, null, null,
                2_200L, "wgp2-self-add-before-cutoff");
        AccountGroupsReportedEvent event = new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 3_000L, List.of(group),
                "evt-before-cutoff", "test", "groups-sync-command", "snapshot-2",
                2_500L, 2_500L, true, 0);

        assertThatThrownBy(() -> service().applyGroupsReported(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号群列表事件上控后证据缺少或早于查询边界");

        verifyNoInteractions(mapper, phaseService);
    }

    @Test
    void snapshotWithoutSkippedCountFailsClosedAsIncomplete() {
        AccountGroupsReportedEvent event = new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 2_000L, event().groups(),
                "evt-missing-skipped", "test", "groups-sync-command", "snapshot-3",
                2_000L, 2_000L, true, null);
        when(mapper.selectContext(10L)).thenReturn(context(1));
        when(phaseService.prepareCompatibility(
                event, com.armada.platform.protocol.model.enums.ProtocolBackend.WEB,
                true, false, 2_000L)).thenReturn(
                        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult.accepted(
                                List.of(), GroupClassificationPlan.empty()));
        when(phaseService.applyCurrentSnapshot(
                event, false, 2_000L, List.of(), GroupClassificationPlan.empty(), true))
                .thenReturn(new AccountGroupMembershipChangeSet(List.of(), List.of()));

        service().applyGroupsReported(event);

        verify(phaseService).applyCurrentSnapshot(
                event, false, 2_000L, List.of(), GroupClassificationPlan.empty(), true);
    }

    @Test
    void negativeSkippedCountIsRejectedBeforeAnyWrite() {
        AccountGroupsReportedEvent event = new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 2_000L, event().groups(),
                "evt-negative-skipped", "test", "groups-sync-command", "snapshot-4",
                2_000L, 2_000L, false, -1);

        assertThatThrownBy(() -> service().applyGroupsReported(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号群列表事件 skippedGroupCount 非法");

        verifyNoInteractions(mapper, phaseService);
    }

    @Test
    void postControlEvidenceWithoutCompleteQueryBoundaryIsRejected() {
        AccountGroupsReportedEvent.Group group = new AccountGroupsReportedEvent.Group(
                "120363001@g.us", "群一", 20, null, null, true, false, null,
                null, null, null, null, false, null, null,
                2_500L, "wgp2-self-add-without-query-start");
        AccountGroupsReportedEvent event = new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 3_000L, List.of(group),
                "evt-missing-query-start", "test", "groups-sync-command", "snapshot-5",
                null, 2_000L, false, 0);

        assertThatThrownBy(() -> service().applyGroupsReported(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号群列表事件上控后证据缺少或早于查询边界");

        verifyNoInteractions(mapper, phaseService);
    }

    private AccountGroupMembershipReportServiceImpl service() {
        return new AccountGroupMembershipReportServiceImpl(mapper, phaseService);
    }

    private static AccountGroupsReportedEvent event() {
        return new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 2_000L,
                List.of(new AccountGroupsReportedEvent.Group(
                        "120363001@g.us", "群一", 20, null, null, true, false, null)),
                "evt", "test", "groups-sync-command", "snapshot-1",
                2_000L, 2_000L, true, 0);
    }

    private static Context context(int state) {
        return context(state, null);
    }

    private static Context context(int state, Long lastCompleteAt) {
        return new Context(10L, 501L, "15550000001", "WEB", "acc-10",
                state, 1, 0, 1_000L, null, lastCompleteAt);
    }
}
