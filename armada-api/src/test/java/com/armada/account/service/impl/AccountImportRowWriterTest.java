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
import com.armada.account.model.enums.AccountCredentialFormatCode;
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
        assertThat(accountCaptor.getValue().getDeclaredAccountType()).isEqualTo(1);
        assertThat(accountCaptor.getValue().getAccountTypeVerifyStatus()).isZero();
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

    @Test
    void writeOne_normalizesParamsImportToAndroidSixCredential() {
        when(accountMapper.insert(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(125L);
            return 1;
        });
        when(stateMapper.insert(any())).thenReturn(1);
        when(credentialMapper.insert(any())).thenReturn(1);
        AccountImportRowWriter writer = new AccountImportRowWriter(
                accountMapper, stateMapper, credentialMapper);

        writer.writeOne("5210000000001", sixEntry(), 9L,
                new AccountImportDTO(9L, ImportFormat.PARAMS.getCode(), 1, 1,
                        "MX", null, null, "params.txt"));

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        ArgumentCaptor<AccountCredential> credentialCaptor =
                ArgumentCaptor.forClass(AccountCredential.class);
        verify(accountMapper).insert(accountCaptor.capture());
        verify(credentialMapper).insert(credentialCaptor.capture());
        assertThat(accountCaptor.getValue().getProtocolId())
                .isEqualTo(ProtocolBackend.ANDROID.name());
        assertThat(accountCaptor.getValue().getDeviceOs()).isEqualTo(1);
        assertThat(credentialCaptor.getValue().getCredFormat())
                .isEqualTo(ImportFormat.SIX.getCode());
    }

    @Test
    void writeOne_preservesIosNativeCredentialAsRuntimeFormatFour() {
        when(accountMapper.insert(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(126L);
            return 1;
        });
        when(stateMapper.insert(any())).thenReturn(1);
        when(credentialMapper.insert(any())).thenReturn(1);
        AccountImportRowWriter writer = new AccountImportRowWriter(
                accountMapper, stateMapper, credentialMapper);

        writer.writeOne("447700900123", iosNativeEntry(), 9L,
                new AccountImportDTO(9L, ImportFormat.PARAMS.getCode(), 2, 2,
                        "GB", null, null, "ios-params.txt"));

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        ArgumentCaptor<AccountCredential> credentialCaptor =
                ArgumentCaptor.forClass(AccountCredential.class);
        verify(accountMapper).insert(accountCaptor.capture());
        verify(credentialMapper).insert(credentialCaptor.capture());
        assertThat(accountCaptor.getValue().getProtocolId()).isEqualTo(ProtocolBackend.ANDROID.name());
        assertThat(accountCaptor.getValue().getDeviceOs()).isEqualTo(2);
        assertThat(credentialCaptor.getValue().getCredFormat())
                .isEqualTo(AccountCredentialFormatCode.IOS_NATIVE_FULL);
        assertThat(credentialCaptor.getValue().getCredsJson())
                .contains("\"platform\":\"smb_ios\"")
                .contains("\"signPreKeySignature\":\"signature\"");
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

    private static ParsedEntry iosNativeEntry() {
        ParsedEntry entry = new ParsedEntry();
        var data = new ObjectMapper().createObjectNode();
        data.put("phone", "447700900123");
        data.put("jid", "447700900123@s.whatsapp.net");
        data.put("platform", "smb_ios");
        data.put("registrationID", 1234567890L);
        data.put("signPreKeySignature", "signature");
        entry.setData(data);
        return entry;
    }
}
