package com.armada.marketing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.vo.MarketingAccountTreeAccountRow;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class MarketingAccountTreeRealtimeServiceTest {

    private final MarketingTaskMapper taskMapper = Mockito.mock(MarketingTaskMapper.class);
    private final AccountGroupMembershipMapper membershipMapper = Mockito.mock(AccountGroupMembershipMapper.class);
    private final AccountParticipatingGroupPort groupPort = Mockito.mock(AccountParticipatingGroupPort.class);
    private final AccountGroupMembershipSnapshotService snapshotService =
            Mockito.mock(AccountGroupMembershipSnapshotService.class);
    private final TransactionTemplate transactionTemplate = Mockito.mock(TransactionTemplate.class);
    private final MarketingAccountTreeRealtimeService service = new MarketingAccountTreeRealtimeService(
            taskMapper,
            membershipMapper,
            groupPort,
            snapshotService,
            new ObjectMapper(),
            transactionTemplate);

    @Test
    void accountTreeOnlyReturnsAccountCandidatesWithoutProtocolQuery() {
        when(taskMapper.selectAccountTreeAccounts(8L)).thenReturn(List.of(accountRow(3L, "923300000003", 2)));

        var tree = service.accountTree(8L);

        assertThat(tree.accounts()).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(3L);
            assertThat(account.wsPhone()).isEqualTo("923300000003");
            assertThat(account.groupsError()).isFalse();
            assertThat(account.groups()).isEmpty();
        });
        verify(groupPort, never()).listBatch(anyList(), anyInt());
    }

    @Test
    void accountGroupsQueriesProtocolForOnlyTheRequestedAccount() {
        MarketingAccountTreeAccountRow row = accountRow(3L, "923300000003", 3);
        when(taskMapper.selectAccountTreeAccount(3L)).thenReturn(row);
        when(groupPort.listBatch(anyList(), anyInt())).thenReturn(List.of(
                new AccountParticipatingGroupResult(row.getProtocolAccountId(), true, List.of(), null)));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            TransactionStatus status = new SimpleTransactionStatus();
            return callback.doInTransaction(status);
        });
        when(snapshotService.replaceVisibleGroups(Mockito.eq(3L), anyList(), Mockito.anyLong()))
                .thenReturn(List.of());

        var account = service.accountGroups(3L);

        assertThat(account.accountId()).isEqualTo(3L);
        assertThat(account.groupsError()).isFalse();
        verify(groupPort).listBatch(List.of("acc_923300000003"), 5);
    }

    private static MarketingAccountTreeAccountRow accountRow(Long accountId, String phone, Integer baselineState) {
        MarketingAccountTreeAccountRow row = new MarketingAccountTreeAccountRow();
        row.setAccountId(accountId);
        row.setWsPhone(phone);
        row.setProtocolAccountId("acc_" + phone);
        row.setGroupBaselineState(baselineState);
        row.setBaselineGroupJidsJson("[]");
        return row;
    }
}
