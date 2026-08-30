package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.enums.AccountTypeVerifyStatusCode;
import com.armada.account.model.enums.BusinessVerificationLevelCode;
import com.armada.account.service.AccountTypeDetectedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 协议账号类型结果的业务落库规则单测。 */
@ExtendWith(MockitoExtension.class)
class AccountTypeVerificationServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountCredentialMapper credentialMapper;

    @Test
    void applyDetected_correctsEffectiveTypeWhenReliableResultDiffersFromDeclaration() {
        Account account = account(1, 1);
        AccountCredential credential = credential(1_788_000_000_000L);
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(accountMapper.updateTypeVerification(any(Account.class), eq(credential.getUpdatedAt())))
                .thenReturn(1);
        AccountTypeVerificationServiceImpl service =
                new AccountTypeVerificationServiceImpl(accountMapper, credentialMapper);

        boolean applied = service.applyDetected(event("BUSINESS_STANDARD"));

        assertThat(applied).isTrue();
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountMapper).updateTypeVerification(captor.capture(), eq(credential.getUpdatedAt()));
        assertThat(captor.getValue().getAccountType()).isEqualTo(2);
        assertThat(captor.getValue().getAccountTypeVerifyStatus())
                .isEqualTo(AccountTypeVerifyStatusCode.CORRECTED);
        assertThat(captor.getValue().getBusinessVerificationLevel())
                .isEqualTo(BusinessVerificationLevelCode.HIGH);
        assertThat(captor.getValue().getBusinessVerificationVerifiedAt())
                .isEqualTo(1_788_048_799_000L);
    }

    @Test
    void applyDetected_unknownKeepsEffectiveTypeAndMarksInconclusive() {
        Account account = account(2, 2);
        AccountCredential credential = credential(1_788_000_000_000L);
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(credentialMapper.selectByAccountId(100L)).thenReturn(credential);
        when(accountMapper.updateTypeVerification(any(Account.class), eq(credential.getUpdatedAt())))
                .thenReturn(1);
        AccountTypeVerificationServiceImpl service =
                new AccountTypeVerificationServiceImpl(accountMapper, credentialMapper);

        service.applyDetected(event("UNKNOWN"));

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountMapper).updateTypeVerification(captor.capture(), eq(credential.getUpdatedAt()));
        assertThat(captor.getValue().getAccountType()).isNull();
        assertThat(captor.getValue().getAccountTypeVerifyStatus())
                .isEqualTo(AccountTypeVerifyStatusCode.INCONCLUSIVE);
    }

    private static Account account(int effectiveType, int declaredType) {
        Account account = new Account();
        account.setId(100L);
        account.setProtocolAccountId("acc_861800000001");
        account.setAccountType(effectiveType);
        account.setDeclaredAccountType(declaredType);
        return account;
    }

    private static AccountCredential credential(long updatedAt) {
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(100L);
        credential.setUpdatedAt(updatedAt);
        return credential;
    }

    private static AccountTypeDetectedEvent event(String detectedType) {
        return new AccountTypeDetectedEvent(
                7L,
                100L,
                "acc_861800000001",
                "oa-1",
                "cmd-1",
                "ANDROID",
                1_788_000_000_000L,
                1,
                detectedType,
                detectedType.startsWith("BUSINESS") ? "HIGH" : "UNKNOWN",
                "business_profile_query",
                1_788_048_799_000L,
                "evt-type-1");
    }
}
