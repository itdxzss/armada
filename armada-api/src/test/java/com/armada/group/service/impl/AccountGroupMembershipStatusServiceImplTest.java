package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMessageSendPermissionRow;
import com.armada.group.model.vo.AccountGroupMembershipStatusRow;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 账号群关系状态服务当前模型单测。 */
class AccountGroupMembershipStatusServiceImplTest {

    private final AccountGroupMembershipMapper mapper = Mockito.mock(AccountGroupMembershipMapper.class);
    private final AccountGroupCurrentSnapshotMapper currentMapper =
            Mockito.mock(AccountGroupCurrentSnapshotMapper.class);
    private final GroupLinkRegistryService registry = Mockito.mock(GroupLinkRegistryService.class);
    private final GroupClassificationService classification = Mockito.mock(GroupClassificationService.class);
    private final AccountGroupCurrentSnapshotPersistenceImpl persistence =
            Mockito.mock(AccountGroupCurrentSnapshotPersistenceImpl.class);
    private final AccountGroupMembershipStatusServiceImpl service =
            new AccountGroupMembershipStatusServiceImpl(
                    mapper, currentMapper, registry, classification, persistence);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void ambiguousRemoveWritesNotInGroupOnlyToCurrentModel() {
        Mockito.when(currentMapper.selectContext(10L)).thenReturn(context());
        Mockito.when(registry.registerAccountObservedGroup(
                Mockito.eq("120363001@g.us"), Mockito.isNull(),
                Mockito.eq(ProtocolBackend.ANDROID), Mockito.anyLong()))
                .thenReturn(20L);

        service.applyMembershipChanged(event("remove"));

        verify(persistence).applySelfMembershipChanged(
                10L, "120363001@g.us", AccountGroupMembershipStatus.NOT_IN_GROUP,
                2_000L, "event-10", "WGP2_REMOVE");
        Mockito.verifyNoInteractions(classification);
    }

    @Test
    void snapshotNotJoinedKeepsDedicatedHighPrioritySource() {
        Mockito.when(currentMapper.selectContext(10L)).thenReturn(context());
        Mockito.when(registry.registerAccountObservedGroup(
                Mockito.eq("120363001@g.us"), Mockito.isNull(),
                Mockito.eq(ProtocolBackend.ANDROID), Mockito.anyLong()))
                .thenReturn(20L);

        service.applyMembershipChanged(new AccountGroupMembershipChangedEvent(
                1L, 10L, "protocol-account-10", "120363001@g.us",
                "remove", 2_000L, "event-snapshot-not-joined",
                AccountGroupMembershipStatusServiceImpl.GROUP_SNAPSHOT_NOT_JOINED_SOURCE));

        verify(persistence).applySelfMembershipChanged(
                10L, "120363001@g.us", AccountGroupMembershipStatus.NOT_IN_GROUP,
                2_000L, "event-snapshot-not-joined", "GROUP_SNAPSHOT_NOT_JOINED");
    }

    @Test
    void preciseAddWritesCurrentMembershipBeforeClassificationTask() {
        Mockito.when(currentMapper.selectContext(10L)).thenReturn(context());
        Mockito.when(registry.registerAccountObservedGroup(
                Mockito.anyString(), Mockito.isNull(), Mockito.any(), Mockito.anyLong()))
                .thenReturn(20L);

        service.applyMembershipChanged(event("add"));

        org.mockito.InOrder order = Mockito.inOrder(classification, persistence);
        order.verify(persistence).applySelfMembershipChanged(
                10L, "120363001@g.us", AccountGroupMembershipStatus.IN_GROUP,
                2_000L, "event-10", "WGP2_ADD");
        order.verify(classification).classifyMembershipAdded(
                Mockito.eq(10L),
                Mockito.eq(new GroupClassificationCandidate(20L, "120363001@g.us", null)),
                Mockito.eq(2_000L), Mockito.anyLong());
    }

    @Test
    void findCurrentStatusesNormalizesAndDeduplicatesKeys() {
        List<AccountGroupMembershipLookup> normalized = List.of(
                new AccountGroupMembershipLookup(10L, "120363001@g.us"));
        Mockito.when(mapper.selectCurrentStatuses(normalized)).thenReturn(List.of(
                new AccountGroupMembershipStatusRow(10L, "120363001@g.us", 3, 2_000L)));

        var result = service.findCurrentStatuses(List.of(
                new AccountGroupMembershipLookup(10L, " 120363001@g.us "),
                new AccountGroupMembershipLookup(10L, "120363001@g.us")));

        assertThat(result).singleElement().satisfies(status ->
                assertThat(status.status()).isEqualTo(AccountGroupMembershipStatus.KICKED_OUT));
    }

    @Test
    void findCurrentMessageSendPermissionsKeepsExplicitFalse() {
        List<AccountGroupMembershipLookup> normalized = List.of(
                new AccountGroupMembershipLookup(10L, "120363001@g.us"));
        Mockito.when(mapper.selectCurrentMessageSendPermissions(normalized)).thenReturn(List.of(
                new AccountGroupMessageSendPermissionRow(
                        10L, "120363001@g.us", Boolean.FALSE)));

        var result = service.findCurrentMessageSendPermissions(normalized);

        assertThat(result).singleElement().satisfies(permission ->
                assertThat(permission.messageSendAllowed()).isFalse());
    }

    private static Context context() {
        return new Context(10L, "15550000001", "ANDROID", "protocol-account-10",
                2, 1, 0, 1_000L, null, null);
    }

    private static AccountGroupMembershipChangedEvent event(String action) {
        return new AccountGroupMembershipChangedEvent(
                1L, 10L, "protocol-account-10", "120363001@g.us",
                action, 2_000L, "event-10", "android_wgp2");
    }
}
