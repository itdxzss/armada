package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 账号上线批量查询 Mapper 真库测试。
 *
 * <p>覆盖批量上线新增的账号/凭据批量查询 SQL,防止 XML foreach、租户拦截器或字段映射问题
 * 到联调时才暴露。</p>
 */
class AccountOnlineMapperDbTest extends DbTestBase {

    @Autowired
    AccountMapper accountMapper;

    @Autowired
    AccountCredentialMapper credentialMapper;

    @Test
    void selectActiveByIdsAndCredentialsByAccountIds_returnInsertedRows() {
        long now = System.currentTimeMillis();
        Account accountA = insertAccount("86150" + (now % 10000000L), now);
        Account accountB = insertAccount("86151" + (now % 10000000L), now);
        insertCredential(accountA, now);
        insertCredential(accountB, now);

        List<Account> accounts = accountMapper.selectActiveByIds(List.of(accountA.getId(), accountB.getId(), -1L));
        List<AccountCredential> credentials = credentialMapper.selectByAccountIds(List.of(accountA.getId(), accountB.getId(), -1L));

        assertThat(accounts).extracting(Account::getId)
                .containsExactlyInAnyOrder(accountA.getId(), accountB.getId());
        assertThat(credentials).extracting(AccountCredential::getAccountId)
                .containsExactlyInAnyOrder(accountA.getId(), accountB.getId());
    }

    private Account insertAccount(String wsPhone, long now) {
        Account account = new Account();
        account.setWsPhone(wsPhone);
        account.setAccountType(1);
        account.setOwnership(1);
        account.setPriority(0);
        account.setProtocolAccountId("acc_" + wsPhone);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountMapper.insert(account);
        return account;
    }

    private void insertCredential(Account account, long now) {
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(account.getId());
        credential.setWsPhone(account.getWsPhone());
        credential.setCredFormat(2);
        credential.setCredsJson("{\"creds\":{},\"keys\":{}}");
        credential.setCreatedAt(now);
        credential.setUpdatedAt(now);
        credentialMapper.insert(credential);
    }
}
