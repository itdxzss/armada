package com.armada.group.service.impl;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.group.service.GroupClassificationService;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.shared.tenant.TenantContext;
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
    @Mock private AccountGroupMembershipSnapshotService snapshotService;
    @Mock private AccountGroupCurrentSnapshotPersistenceImpl persistence;
    @Mock private MarketingNewGroupImmediateSendService immediateSend;
    @Mock private GroupClassificationService classification;

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
        when(snapshotService.replaceVisibleGroups(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(2_000L),
                org.mockito.ArgumentMatchers.eq("evt"),
                org.mockito.ArgumentMatchers.eq("test"),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(group));
        when(persistence.replaceVisibleGroups(
                10L, event.groups(), true, 2_000L, "evt", List.of(group)))
                .thenReturn(new AccountGroupMembershipChangeSet(List.of(group), List.of(group)));

        service().applyGroupsReported(event);

        verify(immediateSend).enqueueNewGroups(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.argThat(rows ->
                        rows.size() == 1 && rows.get(0).groupLinkId().equals(20L)),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private AccountGroupMembershipReportServiceImpl service() {
        return new AccountGroupMembershipReportServiceImpl(
                mapper, snapshotService, persistence, immediateSend, classification);
    }

    private static AccountGroupsReportedEvent event() {
        return new AccountGroupsReportedEvent(
                7L, 10L, "acc-10", 2_000L,
                List.of(new AccountGroupsReportedEvent.Group(
                        "120363001@g.us", "群一", 20, null, null, true, false, null)),
                "evt", "test", true, 0);
    }

    private static Context context(int state) {
        return new Context(10L, "15550000001", "WEB", "acc-10",
                state, 1, 0, 1_000L, null);
    }
}
