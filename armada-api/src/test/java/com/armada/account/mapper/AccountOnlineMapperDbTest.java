package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.account.model.vo.AccountGroupSyncCandidate;
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

    @Test
    void selectGroupSyncCandidates_requiresOnlineNormalAccountWithCapturedBaseline() {
        long now = System.currentTimeMillis();
        Account captured = insertAccount("86156" + (now % 10000000L), now);
        Account pending = insertAccount("86157" + (now % 10000000L), now);
        Account offline = insertAccount("86158" + (now % 10000000L), now);
        insertDefaultState(captured.getId(), now);
        insertDefaultState(pending.getId(), now);
        insertDefaultState(offline.getId(), now);
        markNormalOnline(captured.getId());
        markNormalOnline(pending.getId());
        markNormalOnline(offline.getId());
        jdbc.update("UPDATE account_state SET login_state = ? WHERE account_id = ?",
                AccountLoginStateCode.OFFLINE, offline.getId());
        markBaselineCaptured(captured.getId(), now);
        jdbc.update("UPDATE account SET group_baseline_state = ? WHERE id = ?",
                AccountGroupBaselineStateCode.PENDING, pending.getId());

        List<AccountGroupSyncCandidate> candidates = accountMapper.selectGroupSyncCandidates(
                50,
                AccountLoginStateCode.ONLINE,
                AccountStateCode.NORMAL,
                AccountGroupBaselineStateCode.CAPTURED);

        assertThat(candidates).extracting(AccountGroupSyncCandidate::accountId)
                .contains(captured.getId())
                .doesNotContain(pending.getId(), offline.getId());
    }

    @Test
    void selectGroupSyncCandidates_ordersByOldestGroupSyncRequestWatermark() {
        long now = System.currentTimeMillis();
        Account neverRequested = insertAccount("86159" + (now % 10000000L), now + 30);
        Account oldestRequested = insertAccount("86160" + (now % 10000000L), now + 20);
        Account newestRequested = insertAccount("86161" + (now % 10000000L), now + 10);
        insertDefaultState(neverRequested.getId(), now);
        insertDefaultState(oldestRequested.getId(), now);
        insertDefaultState(newestRequested.getId(), now);
        markNormalOnline(neverRequested.getId());
        markNormalOnline(oldestRequested.getId());
        markNormalOnline(newestRequested.getId());
        markBaselineCaptured(neverRequested.getId(), now);
        markBaselineCaptured(oldestRequested.getId(), now);
        markBaselineCaptured(newestRequested.getId(), now);
        setGroupSyncRequestedAt(oldestRequested.getId(), now - 20_000);
        setGroupSyncRequestedAt(newestRequested.getId(), now - 10_000);

        List<AccountGroupSyncCandidate> candidates = accountMapper.selectGroupSyncCandidates(
                2,
                AccountLoginStateCode.ONLINE,
                AccountStateCode.NORMAL,
                AccountGroupBaselineStateCode.CAPTURED);

        assertThat(candidates).extracting(AccountGroupSyncCandidate::accountId)
                .containsExactly(neverRequested.getId(), oldestRequested.getId());
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

    private void markNormalOnline(Long accountId) {
        jdbc.update("""
                UPDATE account_state
                SET account_state = ?, login_state = ?, risk_status = 1, mute_status = NULL
                WHERE account_id = ?
                """, AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE, accountId);
    }

    private void markBaselineCaptured(Long accountId, long now) {
        jdbc.update("UPDATE account SET group_baseline_state = ? WHERE id = ?",
                AccountGroupBaselineStateCode.CAPTURED, accountId);
        jdbc.update("""
                INSERT INTO account_group_baseline
                    (tenant_id, account_id, baseline_group_jids, group_count, captured_at, created_at, updated_at)
                VALUES (?, ?, '[]', 0, ?, ?, ?)
                """, TEST_TENANT_ID, accountId, now, now, now);
    }

    private void setGroupSyncRequestedAt(Long accountId, long requestedAt) {
        jdbc.update("""
                UPDATE account_group_baseline
                SET last_group_sync_requested_at = ?
                WHERE account_id = ?
                """, requestedAt, accountId);
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
