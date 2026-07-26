package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.service.PromotionAccountProvisionCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionAccountProvisionServiceImplTest {

    @Mock
    private AccountMapper accountMapper;
    @Mock
    private AccountStateMapper stateMapper;
    @Mock
    private AccountCredentialMapper credentialMapper;

    @Test
    void provisionReusesAccountStateAndCredentialTables() {
        when(accountMapper.insertPromotionAccount(any(Account.class))).thenAnswer(invocation -> {
            invocation.<Account>getArgument(0).setId(901L);
            return 1;
        });
        when(stateMapper.insert(any(AccountState.class))).thenReturn(1);
        when(stateMapper.updateLoginAndAccountState(any(AccountState.class))).thenReturn(1);
        when(stateMapper.updateProxySnapshots(any())).thenReturn(1);
        when(credentialMapper.insertPromotionCredential(any(AccountCredential.class))).thenReturn(1);
        PromotionAccountProvisionServiceImpl service = new PromotionAccountProvisionServiceImpl(
                accountMapper, stateMapper, credentialMapper);

        Long accountId = service.provision(new PromotionAccountProvisionCommand(
                "919876543210", 501L, "印度投放", 81L,
                "acc_919876543210", "http://protocol-worker-1:3000",
                "{\"schema\":\"baileys.auth_state.v1\",\"creds\":{},\"keys\":{}}",
                "sticky001", "IN", "provider-a", 2, 1_800_000_000_000L));

        assertThat(accountId).isEqualTo(901L);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountMapper).insertPromotionAccount(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getPromotionChannelId()).isEqualTo(501L);
        assertThat(accountCaptor.getValue().getChannelName()).isEqualTo("印度投放");
        assertThat(accountCaptor.getValue().getProtocolAccountId()).isEqualTo("acc_919876543210");
        assertThat(accountCaptor.getValue().getAccountType()).isEqualTo(2);

        ArgumentCaptor<AccountState> stateCaptor = ArgumentCaptor.forClass(AccountState.class);
        verify(stateMapper).updateLoginAndAccountState(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getAccountState()).isEqualTo(AccountStateCode.NORMAL);
        assertThat(stateCaptor.getValue().getLoginState()).isEqualTo(AccountLoginStateCode.ONLINE);

        ArgumentCaptor<AccountCredential> credentialCaptor = ArgumentCaptor.forClass(AccountCredential.class);
        verify(credentialMapper).insertPromotionCredential(credentialCaptor.capture());
        assertThat(credentialCaptor.getValue().getCredFormat()).isEqualTo(ImportFormat.JSON.getCode());
        assertThat(credentialCaptor.getValue().getProxySessionId()).isEqualTo("sticky001");
        assertThat(credentialCaptor.getValue().getCredsJson()).contains("\"keys\"");
    }
}
