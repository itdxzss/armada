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
    private final AccountGroupMembershipStatusServiceImpl service =
            new AccountGroupMembershipStatusServiceImpl(mapper, registryService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void preciseRemoveStoresLatestExitFactForExport() {
        AccountGroupBaselineRow account = new AccountGroupBaselineRow();
        account.setAccountId(10L);
        account.setProtocolAccountId("protocol-account-10");
        account.setProtocolId("android_wgp2");
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
                .isEqualTo(AccountGroupMembershipStatus.KICKED_OUT.code());
        assertThat(membership.getValue().getLastExitType())
                .isEqualTo(AccountGroupMembershipStatus.KICKED_OUT.code());
        assertThat(membership.getValue().getLastExitedAt()).isEqualTo(2000L);
        assertThat(membership.getValue().getJoinedAt()).isNull();
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
