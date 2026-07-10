package com.armada.marketing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.vo.MarketingAccountTreeAccountRow;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MarketingAccountTreeRealtimeServiceTest {

    private final MarketingTaskMapper taskMapper = Mockito.mock(MarketingTaskMapper.class);
    private final MarketingAccountOccupancyService occupancyService =
            Mockito.mock(MarketingAccountOccupancyService.class);
    private final MarketingAccountTreeRealtimeService service =
            new MarketingAccountTreeRealtimeService(taskMapper, occupancyService);

    @Test
    void accountTreeReturnsChineseStatusAndGroupCount() {
        MarketingAccountTreeAccountRow row = accountRow(3L, "923300000003", 2);
        row.setGroupCount(7);
        when(taskMapper.selectAccountTreeAccounts(8L)).thenReturn(List.of(row));

        var tree = service.accountTree(8L);

        verify(occupancyService).assertAccountGroupAvailable(org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.anyLong());
        assertThat(tree.accounts()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(3L);
            assertThat(account.wsPhone()).isEqualTo("923300000003");
            assertThat(account.status()).isEqualTo("ONLINE");
            assertThat(account.statusText()).isEqualTo("在线");
            assertThat(account.groupCount()).isEqualTo(7);
            assertThat(account.selectable()).isTrue();
            assertThat(account.disabledReason()).isNull();
            assertThat(account.groupsError()).isFalse();
            assertThat(account.groups()).isEmpty();
        });
    }

    @Test
    void accountTreeMarksOfflineAccountsNotSelectableWithChineseReason() {
        MarketingAccountTreeAccountRow row = accountRow(4L, "923300000004", 2);
        row.setLoginState(2);
        when(taskMapper.selectAccountTreeAccounts(8L)).thenReturn(List.of(row));

        var tree = service.accountTree(8L);

        assertThat(tree.accounts()).singleElement().satisfies(account -> {
            assertThat(account.status()).isEqualTo("OFFLINE");
            assertThat(account.statusText()).isEqualTo("离线");
            assertThat(account.groupCount()).isZero();
            assertThat(account.selectable()).isFalse();
            assertThat(account.disabledReason()).isEqualTo("离线");
        });
    }

    @Test
    void accountTreeMarksOnlineButUnavailableAccountWithUsableDisabledReason() {
        MarketingAccountTreeAccountRow row = accountRow(5L, "923300000005", 2);
        row.setAccountState(8);
        when(taskMapper.selectAccountTreeAccounts(8L)).thenReturn(List.of(row));

        var tree = service.accountTree(8L);

        assertThat(tree.accounts()).singleElement().satisfies(account -> {
            assertThat(account.status()).isEqualTo("ONLINE");
            assertThat(account.statusText()).isEqualTo("在线");
            assertThat(account.selectable()).isFalse();
            assertThat(account.disabledReason()).isEqualTo("账号不可用");
        });
    }

    @Test
    void accountGroupsQueriesCurrentDbMembershipForRequestedAccount() {
        MarketingAccountTreeAccountRow row = accountRow(3L, "923300000003", 2);
        row.setGroupCount(1);
        when(taskMapper.selectAccountTreeAccount(3L)).thenReturn(row);
        when(taskMapper.selectDynamicTargetGroups(3L, null))
                .thenReturn(List.of(groupRow(31L, "120363031@g.us", "新群31")));

        var account = service.accountGroups(3L);

        assertThat(account.accountId()).isEqualTo(3L);
        assertThat(account.statusText()).isEqualTo("在线");
        assertThat(account.groupCount()).isEqualTo(1);
        assertThat(account.groupsError()).isFalse();
        assertThat(account.groups()).singleElement().satisfies(group -> {
            assertThat(group.groupLinkId()).isEqualTo(31L);
            assertThat(group.groupJid()).isEqualTo("120363031@g.us");
            assertThat(group.groupName()).isEqualTo("新群31");
        });
    }

    @Test
    void accountGroupsDoesNotLoadGroupsForPendingBaseline() {
        MarketingAccountTreeAccountRow row = accountRow(4L, "923300000004", 1);
        when(taskMapper.selectAccountTreeAccount(4L)).thenReturn(row);

        var account = service.accountGroups(4L);

        assertThat(account.selectable()).isFalse();
        assertThat(account.disabledReason()).isEqualTo("群同步中");
        assertThat(account.groupsError()).isFalse();
        assertThat(account.groups()).isEmpty();
    }

    private static MarketingAccountTreeAccountRow accountRow(Long accountId, String phone, Integer baselineState) {
        MarketingAccountTreeAccountRow row = new MarketingAccountTreeAccountRow();
        row.setAccountId(accountId);
        row.setWsPhone(phone);
        row.setProtocolAccountId("acc_" + phone);
        row.setGroupBaselineState(baselineState);
        row.setBaselineGroupJidsJson("[]");
        row.setAccountState(2);
        row.setLoginState(1);
        row.setRiskStatus(1);
        row.setMuteStatus(null);
        row.setGroupCount(0);
        return row;
    }

    private static MarketingTargetCandidateRow groupRow(Long groupLinkId, String groupJid, String groupName) {
        MarketingTargetCandidateRow row = new MarketingTargetCandidateRow();
        row.setAccountId(3L);
        row.setAccountPhone("923300000003");
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(groupJid);
        row.setGroupLinkUrl("https://chat.whatsapp.com/" + groupLinkId);
        row.setGroupName(groupName);
        return row;
    }
}
