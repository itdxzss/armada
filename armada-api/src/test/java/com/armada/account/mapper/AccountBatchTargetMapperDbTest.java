package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.dto.AccountBatchQueryDTO;
import com.armada.account.model.dto.AccountBatchTargetQuery;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountBatchPreviewRow;
import com.armada.account.model.vo.AccountBatchTargetRow;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 批量账号目标查询真库测试。
 *
 * <p>验证预估和游标扫描复用账号列表筛选口径，并由真实租户拦截器限制数据范围。</p>
 */
class AccountBatchTargetMapperDbTest extends DbTestBase {

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AccountStateMapper stateMapper;

    @Autowired
    private AccountCredentialMapper credentialMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void previewAndCursorUseSameFiltersAndExclusiveSkipPrecedence() {
        long now = System.currentTimeMillis();
        String phonePrefix = "86139" + now % 1_000_000L;
        Account first = seedTarget(phonePrefix + "01", AccountStateCode.NORMAL, "印度", true, now);
        Account second = seedTarget(phonePrefix + "02", AccountStateCode.NORMAL, "印度", true, now + 1);
        Account banned = seedTarget(phonePrefix + "03", AccountStateCode.BANNED, "印度", false, now + 2);
        seedTarget(phonePrefix + "04", AccountStateCode.NORMAL, AccountLoginStateCode.PENDING_ONLINE,
                "印度", true, now + 3);
        seedTarget(phonePrefix + "05", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE,
                "印度", true, now + 4);
        seedTarget(phonePrefix + "06", AccountStateCode.NORMAL, "美国", true, now + 5);
        AccountQuery query = new AccountBatchQueryDTO(
                null, phonePrefix, null, null, null, null, null, null,
                null, null, null, "印度", null).toAccountQuery();

        AccountBatchPreviewRow preview = accountMapper.previewBatchTargetsByQuery(query);
        List<AccountBatchTargetRow> rows = accountMapper.selectBatchTargetsAfterId(
                AccountBatchTargetQuery.from(query, 0L, 2));

        assertThat(preview.getMatched()).isEqualTo(5);
        assertThat(preview.getBanned()).isEqualTo(1);
        assertThat(preview.getAlreadyPending()).isEqualTo(1);
        assertThat(preview.getAlreadyOnline()).isEqualTo(1);
        assertThat(preview.getMissingCredential()).isZero();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(AccountBatchTargetRow::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(rows).allMatch(AccountBatchTargetRow::isCredentialPresent);
        assertThat(rows).extracting(AccountBatchTargetRow::getId).doesNotContain(banned.getId());
    }

    @Test
    void cursorScansBeyondFirstBatchWithoutDuplicates() {
        long now = System.currentTimeMillis();
        String phonePrefix = "86138" + now % 1_000_000L;
        seedTarget(phonePrefix + "01", AccountStateCode.NORMAL, "印度", true, now);
        seedTarget(phonePrefix + "02", AccountStateCode.NORMAL, "印度", true, now + 1);
        seedTarget(phonePrefix + "03", AccountStateCode.NORMAL, "印度", true, now + 2);
        seedTarget(phonePrefix + "04", AccountStateCode.NORMAL, "印度", true, now + 3);
        AccountQuery query = new AccountBatchQueryDTO(
                null, phonePrefix, null, null, null, null, null, null,
                null, null, null, null, null).toAccountQuery();

        List<AccountBatchTargetRow> first = accountMapper.selectBatchTargetsAfterId(
                AccountBatchTargetQuery.from(query, 0L, 2));
        List<AccountBatchTargetRow> second = accountMapper.selectBatchTargetsAfterId(
                AccountBatchTargetQuery.from(query, first.get(1).getId(), 2));

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(first).extracting(AccountBatchTargetRow::getId)
                .doesNotContainAnyElementsOf(second.stream().map(AccountBatchTargetRow::getId).toList());
        assertThat(second.get(0).getId()).isGreaterThan(first.get(1).getId());
    }

    private Account seedTarget(
            String phone,
            int stateCode,
            String country,
            boolean withCredential,
            long now) {
        return seedTarget(phone, stateCode, null, country, withCredential, now);
    }

    private Account seedTarget(
            String phone,
            int stateCode,
            Integer loginState,
            String country,
            boolean withCredential,
            long now) {
        Account account = new Account();
        account.setWsPhone(phone);
        account.setAccountType(1);
        account.setOwnership(1);
        account.setPriority(0);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountMapper.insert(account);

        AccountState state = new AccountState();
        state.setAccountId(account.getId());
        state.setProxyFailureCount(0);
        state.setPullIntoGroupCount(0);
        state.setCreatedAt(now);
        state.setUpdatedAt(now);
        stateMapper.insert(state);
        jdbcTemplate.update("""
                UPDATE account_state
                SET account_state = ?, login_state = ?, proxy_country = ?
                WHERE account_id = ?
                """, stateCode, loginState, country, account.getId());

        if (withCredential) {
            AccountCredential credential = new AccountCredential();
            credential.setAccountId(account.getId());
            credential.setWsPhone(phone);
            credential.setCredFormat(2);
            credential.setCredsJson("{\"creds\":{},\"keys\":{}}");
            credential.setCreatedAt(now);
            credential.setUpdatedAt(now);
            credentialMapper.insert(credential);
        }
        return account;
    }
}
