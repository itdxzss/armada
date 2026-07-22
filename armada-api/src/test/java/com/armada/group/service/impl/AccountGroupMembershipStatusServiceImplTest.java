package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMembershipStatusRow;
import com.armada.group.service.GroupLinkRegistryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 账号群关系状态批量读取服务单测。 */
class AccountGroupMembershipStatusServiceImplTest {

    private final AccountGroupMembershipMapper mapper = Mockito.mock(AccountGroupMembershipMapper.class);
    private final GroupLinkRegistryService registryService = Mockito.mock(GroupLinkRegistryService.class);
    private final AccountGroupMembershipStatusServiceImpl service =
            new AccountGroupMembershipStatusServiceImpl(mapper, registryService);

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
