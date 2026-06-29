package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.vo.AccountIpRegionRow;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Autowired
    AccountStateMapper stateMapper;

    @Autowired
    AccountImportBatchMapper batchMapper;

    @Autowired
    AccountImportDetailMapper detailMapper;

    @Autowired
    JdbcTemplate jdbc;

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

    @Test
    void selectOnlineAccountIdsByIds_returnsOnlyOnlineAccounts() {
        long now = System.currentTimeMillis();
        Account online = insertAccount("86152" + (now % 10000000L), now);
        Account offline = insertAccount("86153" + (now % 10000000L), now);
        Account noState = insertAccount("86154" + (now % 10000000L), now);
        insertDefaultState(online.getId(), now);
        insertDefaultState(offline.getId(), now);
        jdbc.update("UPDATE account_state SET login_state = ? WHERE account_id = ?",
                AccountLoginStateCode.ONLINE, online.getId());
        jdbc.update("UPDATE account_state SET login_state = ? WHERE account_id = ?",
                AccountLoginStateCode.OFFLINE, offline.getId());

        List<Long> accountIds = accountMapper.selectOnlineAccountIdsByIds(
                List.of(online.getId(), offline.getId(), noState.getId()),
                AccountLoginStateCode.ONLINE);

        assertThat(accountIds).containsExactly(online.getId());
    }

    @Test
    void selectIpRegionsByAccountIds_returnsImportedBatchRegion() {
        long now = System.currentTimeMillis();
        Account account = insertAccount("86155" + (now % 10000000L), now);
        AccountImportBatch batch = insertImportBatch("印度", now);
        insertSuccessDetail(batch.getId(), account, now);

        List<AccountIpRegionRow> rows = accountMapper.selectIpRegionsByAccountIds(
                List.of(account.getId(), -1L),
                ImportResult.SUCCESS.getCode());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAccountId()).isEqualTo(account.getId());
        assertThat(rows.get(0).getIpRegion()).isEqualTo("印度");
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

    private void insertDefaultState(Long accountId, long now) {
        AccountState state = new AccountState();
        state.setAccountId(accountId);
        state.setProxyFailureCount(0);
        state.setPullIntoGroupCount(0);
        state.setCreatedAt(now);
        state.setUpdatedAt(now);
        stateMapper.insert(state);
    }

    private AccountImportBatch insertImportBatch(String ipRegion, long now) {
        AccountImportBatch batch = new AccountImportBatch();
        batch.setAccountGroupId(1L);
        batch.setSourceFileName("dbtest.txt");
        batch.setImportFormat(2);
        batch.setDeviceOs(1);
        batch.setAccountType(1);
        batch.setIpRegion(ipRegion);
        batch.setTotalRows(1);
        batch.setImportedRows(1);
        batch.setDuplicateRows(0);
        batch.setFormatErrorRows(0);
        batch.setStatus(2);
        batch.setCreatedAt(now);
        batchMapper.insert(batch);
        return batch;
    }

    private void insertSuccessDetail(Long batchId, Account account, long now) {
        AccountImportDetail detail = new AccountImportDetail();
        detail.setBatchId(batchId);
        detail.setLineNo(1);
        detail.setWsPhone(account.getWsPhone());
        detail.setAccountId(account.getId());
        detail.setParseResult(ImportResult.SUCCESS.getCode());
        detail.setCreatedAt(now);
        detailMapper.batchInsert(List.of(detail));
    }
}
