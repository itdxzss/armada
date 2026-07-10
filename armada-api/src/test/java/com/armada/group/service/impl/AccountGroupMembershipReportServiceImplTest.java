package com.armada.group.service.impl;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
    void applyGroupsReported_capturesPendingBaselineAndClearsVisibleMembership() {
        AccountGroupBaselineRow row = new AccountGroupBaselineRow();
        row.setAccountId(10L);
        row.setGroupBaselineState(AccountGroupBaselineStateCode.PENDING);
        when(membershipMapper.selectAccountBaselineRow(10L)).thenReturn(row);
        when(membershipMapper.capturePendingAccountGroupBaseline(
                eq(10L), eq("[\"120363old@g.us\"]"), eq(1), eq(1782626401000L), anyLong()))
                .thenReturn(1);
        when(membershipMapper.markAccountBaselineCaptured(eq(10L), anyLong())).thenReturn(1);

        service.applyGroupsReported(new AccountGroupsReportedEvent(
                1L,
                10L,
                "acc_10",
                1782626401000L,
                List.of(
                        new AccountGroupsReportedEvent.Group(
                                "120363old@g.us", "导入时旧群", 10, null, null, false, false, null),
                        new AccountGroupsReportedEvent.Group(
                                "120363old@g.us", "重复旧群", 10, null, null, false, false, null),
                        new AccountGroupsReportedEvent.Group(
                                " ", "空 JID", 0, null, null, false, false, null)),
                "evt-pending-baseline"));

        verify(membershipMapper).capturePendingAccountGroupBaseline(
                eq(10L), eq("[\"120363old@g.us\"]"), eq(1), eq(1782626401000L), anyLong());
        verify(membershipMapper).markAccountBaselineCaptured(eq(10L), anyLong());
        verify(snapshotService).replaceVisibleGroups(
                eq(10L),
                argThat(groups -> groups != null && groups.isEmpty()),
                eq(1782626401000L),
                eq("evt-pending-baseline"),
                eq(null));
    }

    @Test
    void applyGroupsReported_propagatesCorrelationWhenReplacingVisibleGroups() {
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
                argThat(groups -> groups.size() == 1
                        && "120363new@g.us".equals(groups.get(0).groupJid())),
                eq(1782626401000L),
                eq("evt-visible"),
                eq("wa_groups_dirty"));
    }
}
