package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMembershipStatusRow;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** 账号群关系状态批量读取服务单测。 */
class AccountGroupMembershipStatusServiceImplTest {

    private final AccountGroupMembershipMapper mapper = Mockito.mock(AccountGroupMembershipMapper.class);
    private final GroupLinkRegistryService registryService = Mockito.mock(GroupLinkRegistryService.class);
    private final GroupClassificationService classificationService =
            Mockito.mock(GroupClassificationService.class);
    private final AccountGroupCurrentSnapshotPersistenceImpl currentPersistence =
            Mockito.mock(AccountGroupCurrentSnapshotPersistenceImpl.class);
    private final AccountGroupMembershipStatusServiceImpl service =
            new AccountGroupMembershipStatusServiceImpl(
                    mapper, registryService, classificationService, currentPersistence);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void ambiguousRemoveStoresNotInGroupInsteadOfKickedOut() {
        AccountGroupBaselineRow account = new AccountGroupBaselineRow();
        account.setAccountId(10L);
        account.setProtocolAccountId("protocol-account-10");
        account.setProtocolId("ANDROID");
        Mockito.when(mapper.selectAccountBaselineRow(10L)).thenReturn(account);
        Mockito.when(registryService.registerAccountObservedGroup(
                Mockito.eq("120363001@g.us"),
                Mockito.isNull(),
                Mockito.eq(ProtocolBackend.ANDROID),
                Mockito.anyLong()))
                .thenReturn(20L);

        service.applyMembershipChanged(new AccountGroupMembershipChangedEvent(
                1L,
                10L,
                "protocol-account-10",
                "120363001@g.us",
                "remove",
                2000L,
                "event-remove-10",
                "android_wgp2"));

        ArgumentCaptor<AccountGroupMembership> membership =
                ArgumentCaptor.forClass(AccountGroupMembership.class);
        verify(mapper).upsertMembership(membership.capture());
        assertThat(membership.getValue().getMembershipStatus())
                .isEqualTo(AccountGroupMembershipStatus.NOT_IN_GROUP.code());
        assertThat(membership.getValue().getLastExitType())
                .isEqualTo(AccountGroupMembershipStatus.NOT_IN_GROUP.code());
        assertThat(membership.getValue().getLastExitedAt()).isEqualTo(2000L);
        assertThat(membership.getValue().getJoinedAt()).isNull();
        Mockito.verifyNoInteractions(classificationService);
        verify(currentPersistence).applySelfMembershipChanged(
                10L,
                "120363001@g.us",
                AccountGroupMembershipStatus.NOT_IN_GROUP,
                2000L,
                "event-remove-10",
                "WGP2_REMOVE");
    }

    @Test
    void preciseAddClassifiesPostControlBeforeWritingMembership() {
        AccountGroupBaselineRow account = new AccountGroupBaselineRow();
        account.setAccountId(10L);
        account.setProtocolAccountId("protocol-account-10");
        account.setProtocolId("ANDROID");
        Mockito.when(mapper.selectAccountBaselineRow(10L)).thenReturn(account);
        Mockito.when(registryService.registerAccountObservedGroup(
                Mockito.eq("120363001@g.us"),
                Mockito.isNull(),
                Mockito.eq(ProtocolBackend.ANDROID),
                Mockito.anyLong()))
                .thenReturn(20L);

        service.applyMembershipChanged(new AccountGroupMembershipChangedEvent(
                1L,
                10L,
                "protocol-account-10",
                "120363001@g.us",
                "add",
                2_000L,
                "event-add-10",
                "android_wgp2"));

        org.mockito.InOrder order = Mockito.inOrder(classificationService, mapper);
        order.verify(classificationService).classifyMembershipAdded(
                Mockito.eq(10L),
                Mockito.eq(new GroupClassificationCandidate(
                        20L, "120363001@g.us", null)),
                Mockito.eq(2_000L),
                Mockito.anyLong());
        order.verify(mapper).upsertMembership(Mockito.any(AccountGroupMembership.class));
        verify(currentPersistence).applySelfMembershipChanged(
                10L,
                "120363001@g.us",
                AccountGroupMembershipStatus.IN_GROUP,
                2_000L,
                "event-add-10",
                "WGP2_ADD");
    }

    @Test
    void findCurrentStatusesNormalizesAndDeduplicatesKeys() {
        List<AccountGroupMembershipLookup> normalized = List.of(
                new AccountGroupMembershipLookup(10L, "120363001@g.us"));
        Mockito.when(mapper.selectCurrentStatuses(normalized)).thenReturn(List.of(
                new AccountGroupMembershipStatusRow(10L, "120363001@g.us", 3, 2000L)));

        var result = service.findCurrentStatuses(List.of(
                new AccountGroupMembershipLookup(10L, " 120363001@g.us "),
                new AccountGroupMembershipLookup(10L, "120363001@g.us"),
                new AccountGroupMembershipLookup(null, "ignored@g.us"),
                new AccountGroupMembershipLookup(10L, " ")));

        verify(mapper).selectCurrentStatuses(normalized);
        assertThat(result).singleElement().satisfies(status -> {
            assertThat(status.accountId()).isEqualTo(10L);
            assertThat(status.groupJid()).isEqualTo("120363001@g.us");
            assertThat(status.status()).isEqualTo(AccountGroupMembershipStatus.KICKED_OUT);
            assertThat(status.statusUpdatedAt()).isEqualTo(2000L);
        });
    }

    @Test
    void findCurrentStatusesReturnsEmptyWithoutCallingMapperForEmptyKeys() {
        assertThat(service.findCurrentStatuses(List.of())).isEmpty();
        Mockito.verifyNoInteractions(mapper);
    }
}
