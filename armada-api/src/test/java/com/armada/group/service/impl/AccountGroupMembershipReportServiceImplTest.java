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
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AccountGroupMembershipReportServiceImplTest {

    private final AccountGroupMembershipMapper membershipMapper = Mockito.mock(AccountGroupMembershipMapper.class);
    private final AccountGroupMembershipSnapshotService snapshotService =
            Mockito.mock(AccountGroupMembershipSnapshotService.class);
    private final AccountGroupMembershipReportServiceImpl service =
            new AccountGroupMembershipReportServiceImpl(membershipMapper, snapshotService, new ObjectMapper());

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
}
