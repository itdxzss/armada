package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountRestrictionServiceImplTest {

    @Mock
    private AccountStateMapper stateMapper;

    @Mock
    private ProtocolCommandOutboxService outboxService;

    @Test
    void markGroupCreateRestrictedMarksAccountRestrictedOfflineAndEnqueuesOffline() {
        long occurredAt = 1_725_000_000_000L;

        new AccountRestrictionServiceImpl(stateMapper, outboxService)
                .markGroupCreateRestricted(7L, "acc_7", "rate-overlimit", occurredAt);

        ArgumentCaptor<AccountState> lifecycleCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateLifecycleState(lifecycleCaptor.capture());
        AccountState lifecycle = lifecycleCaptor.getValue();
        assertThat(lifecycle.getAccountId()).isEqualTo(7L);
        assertThat(lifecycle.getAccountState()).isEqualTo(AccountStateCode.RESTRICTED);
        assertThat(lifecycle.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(lifecycle.getStateSource()).isEqualTo("GROUP_CREATE_RESTRICTED");
        assertThat(lifecycle.getLastStateSyncTime()).isEqualTo(occurredAt);

        ArgumentCaptor<AccountState> reasonCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateBlockReason(reasonCaptor.capture());
        assertThat(reasonCaptor.getValue().getBlockReason()).isEqualTo("rate-overlimit");

        ArgumentCaptor<List<ProtocolOfflineCommandRequest>> offlineCaptor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueOfflineCommands(offlineCaptor.capture());
        assertThat(offlineCaptor.getValue()).singleElement().satisfies(command -> {
            assertThat(command.accountId()).isEqualTo(7L);
            assertThat(command.protocolAccountId()).isEqualTo("acc_7");
            assertThat(command.source()).isEqualTo("group_create_restricted");
        });
    }

    @Test
    void markGroupCreateRestrictedSkipsOfflineCommandWhenProtocolAccountIdMissing() {
        new AccountRestrictionServiceImpl(stateMapper, outboxService)
                .markGroupCreateRestricted(7L, "", "account_reachout_restricted", 1_725_000_000_000L);

        verify(stateMapper).updateLifecycleState(any(AccountState.class));
        verify(stateMapper).updateBlockReason(any(AccountState.class));
        verifyNoInteractions(outboxService);
    }
}
