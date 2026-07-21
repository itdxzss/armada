package com.armada.group.service.impl;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AccountGroupMembershipReportServiceImplTest {

    private final AccountGroupMembershipMapper membershipMapper = Mockito.mock(AccountGroupMembershipMapper.class);
    private final AccountGroupMembershipSnapshotService snapshotService =
            Mockito.mock(AccountGroupMembershipSnapshotService.class);
    private final MarketingNewGroupImmediateSendService immediateSendService =
            Mockito.mock(MarketingNewGroupImmediateSendService.class);
    private final AccountGroupMembershipReportServiceImpl service =
            new AccountGroupMembershipReportServiceImpl(
                    membershipMapper, snapshotService, immediateSendService, new ObjectMapper());

    @BeforeEach
    void stubEmptyChanges() {
        when(snapshotService.replaceVisibleGroups(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AccountGroupMembershipChangeSet(List.of(), List.of()));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void applyGroupsReported_capturesPendingBaselineAndSavesAllReportedGroups() {
        AccountGroupBaselineRow row = new AccountGroupBaselineRow();
        row.setAccountId(10L);
        row.setGroupBaselineState(AccountGroupBaselineStateCode.PENDING);
        when(membershipMapper.selectAccountBaselineRow(10L)).thenReturn(row);
        when(membershipMapper.capturePendingAccountGroupBaseline(
                argThat(baseline -> Long.valueOf(10L).equals(baseline.getAccountId())
                        && "[\"120363old@g.us\"]".equals(baseline.getBaselineGroupJidsJson())
                        && "{\"120363old@g.us\":\"导入时旧群\"}"
                        .equals(baseline.getBaselineGroupSubjectsJson())
                        && Integer.valueOf(1).equals(baseline.getGroupCount())),
                eq(1782626401000L), anyLong()))
                .thenReturn(1);
        when(membershipMapper.markAccountBaselineCaptured(eq(10L), anyLong())).thenReturn(1);

        service.applyGroupsReported(new AccountGroupsReportedEvent(
                1L,
                10L,
                "acc_10",
                1782626401000L,
                List.of(
                        new AccountGroupsReportedEvent.Group(
                                " 120363old@g.us ", " ", 10, null, null, false, false, null),
                        new AccountGroupsReportedEvent.Group(
                                "120363old@g.us", " 导入时旧群 ", 10, null, null, false, false, null),
                        new AccountGroupsReportedEvent.Group(
                                "120363old@g.us", "重复旧群", 10, null, null, false, false, null),
                        new AccountGroupsReportedEvent.Group(
                                " ", "空 JID", 0, null, null, false, false, null)),
                "evt-pending-baseline"));

        verify(membershipMapper).capturePendingAccountGroupBaseline(
                argThat(baseline -> Long.valueOf(10L).equals(baseline.getAccountId())
                        && "[\"120363old@g.us\"]".equals(baseline.getBaselineGroupJidsJson())
                        && "{\"120363old@g.us\":\"导入时旧群\"}"
                        .equals(baseline.getBaselineGroupSubjectsJson())
                        && Integer.valueOf(1).equals(baseline.getGroupCount())),
                eq(1782626401000L), anyLong());
        verify(membershipMapper).markAccountBaselineCaptured(eq(10L), anyLong());
        verify(snapshotService).replaceVisibleGroups(
                eq(10L),
                argThat(groups -> groups != null
                        && groups.size() == 4
                        && "120363old@g.us".equals(groups.get(1).groupJid().trim())),
                eq(1782626401000L),
                eq("evt-pending-baseline"),
                eq(null));
    }

    @Test
    void applyGroupsReported_legacyGroupsWithoutSubjectsCaptureEmptySubjectMap() {
        AccountGroupBaselineRow row = new AccountGroupBaselineRow();
        row.setAccountId(11L);
        row.setGroupBaselineState(AccountGroupBaselineStateCode.PENDING);
        when(membershipMapper.selectAccountBaselineRow(11L)).thenReturn(row);
        when(membershipMapper.capturePendingAccountGroupBaseline(
                argThat(baseline -> "[\"120363legacy@g.us\"]".equals(baseline.getBaselineGroupJidsJson())
                        && "{}".equals(baseline.getBaselineGroupSubjectsJson())),
                eq(1782626402000L), anyLong()))
                .thenReturn(1);
        when(membershipMapper.markAccountBaselineCaptured(eq(11L), anyLong())).thenReturn(1);

        service.applyGroupsReported(new AccountGroupsReportedEvent(
                1L,
                11L,
                "acc_11",
                1782626402000L,
                List.of(new AccountGroupsReportedEvent.Group(
                        "120363legacy@g.us", null, null, null, null, false, false, null)),
                "evt-legacy-baseline"));

        verify(membershipMapper).capturePendingAccountGroupBaseline(
                argThat(baseline -> "[\"120363legacy@g.us\"]".equals(baseline.getBaselineGroupJidsJson())
                        && "{}".equals(baseline.getBaselineGroupSubjectsJson())),
                eq(1782626402000L), anyLong());
    }

    @Test
    void applyGroupsReported_capturedBaselineStillSavesBaselineAndNewGroups() {
        AccountGroupBaselineRow row = new AccountGroupBaselineRow();
        row.setAccountId(10L);
        row.setGroupBaselineState(AccountGroupBaselineStateCode.CAPTURED);
        row.setBaselineGroupJidsJson("[\"120363old@g.us\"]");
        when(membershipMapper.selectAccountBaselineRow(10L)).thenReturn(row);

        service.applyGroupsReported(new AccountGroupsReportedEvent(
                1L,
                10L,
                "acc_10",
                1782626401000L,
                List.of(
                        new AccountGroupsReportedEvent.Group(
                                "120363old@g.us", "导入时旧群", 10, null, null, false, false, null),
                        new AccountGroupsReportedEvent.Group(
                                "120363new@g.us", "新群", 11, null, null, true, false, null)),
                "evt-visible",
                "wa_groups_dirty"));

        verify(snapshotService).replaceVisibleGroups(
                eq(10L),
                argThat(groups -> groups.size() == 2
                        && "120363old@g.us".equals(groups.get(0).groupJid())
                        && "120363new@g.us".equals(groups.get(1).groupJid())),
                eq(1782626401000L),
                eq("evt-visible"),
                eq("wa_groups_dirty"));
        verify(membershipMapper, never()).capturePendingAccountGroupBaseline(
                org.mockito.ArgumentMatchers.any(AccountGroupBaselineRow.class), anyLong(), anyLong());
    }

    @Test
    void applyGroupsReported_pendingBaselineDoesNotTriggerImmediateMarketing() {
        AccountGroupBaselineRow row = baseline(10L, AccountGroupBaselineStateCode.PENDING);
        when(membershipMapper.selectAccountBaselineRow(10L)).thenReturn(row);
        when(membershipMapper.capturePendingAccountGroupBaseline(
                org.mockito.ArgumentMatchers.any(), anyLong(), anyLong())).thenReturn(1);
        when(membershipMapper.markAccountBaselineCaptured(eq(10L), anyLong())).thenReturn(1);
        when(snapshotService.replaceVisibleGroups(
                eq(10L), org.mockito.ArgumentMatchers.any(), anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(changeSet("120363old@g.us"));

        service.applyGroupsReported(event(10L, "120363old@g.us"));

        verify(immediateSendService, never()).enqueueNewGroups(anyLong(),
                org.mockito.ArgumentMatchers.any(), anyLong());
    }

    @Test
    void applyGroupsReported_capturedBaselineTriggersOnlyAddedGroups() {
        AccountGroupBaselineRow row = baseline(10L, AccountGroupBaselineStateCode.CAPTURED);
        when(membershipMapper.selectAccountBaselineRow(10L)).thenReturn(row);
        when(snapshotService.replaceVisibleGroups(
                eq(10L), org.mockito.ArgumentMatchers.any(), anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(changeSet("120363new@g.us"));

        service.applyGroupsReported(event(10L, "120363new@g.us"));

        verify(immediateSendService).enqueueNewGroups(
                eq(10L),
                argThat(groups -> groups.size() == 1
                        && "120363new@g.us".equals(groups.get(0).groupJid())),
                anyLong());
    }

    private static AccountGroupBaselineRow baseline(Long accountId, Integer state) {
        AccountGroupBaselineRow row = new AccountGroupBaselineRow();
        row.setAccountId(accountId);
        row.setGroupBaselineState(state);
        return row;
    }

    private static AccountGroupMembershipChangeSet changeSet(String groupJid) {
        AccountGroupMembershipSnapshot group = new AccountGroupMembershipSnapshot(
                301L, groupJid, "新群", "wa://group/" + groupJid, false);
        return new AccountGroupMembershipChangeSet(List.of(group), List.of(group));
    }

    private static AccountGroupsReportedEvent event(Long accountId, String groupJid) {
        return new AccountGroupsReportedEvent(
                1L,
                accountId,
                "acc_" + accountId,
                1_782_626_401_000L,
                List.of(new AccountGroupsReportedEvent.Group(
                        groupJid, "新群", 10, null, null, false, false, null)),
                "evt_" + accountId,
                "wa_groups_dirty");
    }
}
