package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.entity.ParsedEntry;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountImportRowWriterTest {

    @Mock
    private AccountMapper accountMapper;
    @Mock
    private AccountStateMapper stateMapper;
    @Mock
    private AccountCredentialMapper credentialMapper;

    @Test
    void writeOne_marksSixImportAsAndroidProtocol() {
        when(accountMapper.insert(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(123L);
            return 1;
        });
        when(stateMapper.insert(any())).thenReturn(1);
        when(credentialMapper.insert(any())).thenReturn(1);
        AccountImportRowWriter writer = new AccountImportRowWriter(accountMapper, stateMapper, credentialMapper);

        Long accountId = writer.writeOne("27612057408", sixEntry(), 9L,
                new AccountImportDTO(9L, ImportFormat.SIX.getCode(), 1, 1, "ZA", null, null, "six.txt"));

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        ArgumentCaptor<AccountCredential> credentialCaptor = ArgumentCaptor.forClass(AccountCredential.class);
        verify(accountMapper).insert(accountCaptor.capture());
        verify(credentialMapper).insert(credentialCaptor.capture());
        assertThat(accountId).isEqualTo(123L);
        assertThat(accountCaptor.getValue().getProtocolId()).isEqualTo(ProtocolBackend.ANDROID.name());
        assertThat(accountCaptor.getValue().getProtocolAccountId()).isEqualTo("acc_27612057408");
        assertThat(credentialCaptor.getValue().getCredFormat()).isEqualTo(ImportFormat.SIX.getCode());
        assertThat(credentialCaptor.getValue().getCredsJson()).contains("\"phone\":\"27612057408\"");
    }

    @Test
    void writeOne_marksJsonImportAsWebProtocol() {
        when(accountMapper.insert(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(124L);
            return 1;
        });
        when(stateMapper.insert(any())).thenReturn(1);
        when(credentialMapper.insert(any())).thenReturn(1);
        AccountImportRowWriter writer = new AccountImportRowWriter(
                accountMapper, stateMapper, credentialMapper);

        ParsedEntry entry = new ParsedEntry();
        var data = new ObjectMapper().createObjectNode();
        data.put("me", "json-account");
        entry.setData(data);

        Long accountId = writer.writeOne("27612057409", entry, 9L,
                new AccountImportDTO(9L, ImportFormat.JSON.getCode(), 1, 1,
                        "ZA", null, null, "account.json"));

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        ArgumentCaptor<AccountCredential> credentialCaptor =
                ArgumentCaptor.forClass(AccountCredential.class);
        verify(accountMapper).insert(accountCaptor.capture());
        verify(credentialMapper).insert(credentialCaptor.capture());
        assertThat(accountId).isEqualTo(124L);
        assertThat(accountCaptor.getValue().getProtocolId())
                .isEqualTo(ProtocolBackend.WEB.name());
        assertThat(credentialCaptor.getValue().getCredFormat())
                .isEqualTo(ImportFormat.JSON.getCode());
    }

    private static ParsedEntry sixEntry() {
        ParsedEntry entry = new ParsedEntry();
        ObjectMapper mapper = new ObjectMapper();
        var data = mapper.createObjectNode();
        data.put("phone", "27612057408");
        data.put("id_pri_key", "id-pri");
        data.put("id_pub_key", "id-pub");
        data.put("static_pri_key", "static-pri");
        data.put("static_pub_key", "static-pub");
        data.put("device_identity_key", "device-identity");
        entry.setData(data);
        return entry;
    }
}
