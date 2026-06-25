package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AccountService 真库集成测试:验迁移分组、严格批量删除(全或无)。
 *
 * <p>每个 @Test 在 @Transactional 内执行并回滚,互不干扰。</p>
 * <p>构造 account_state=4(导出)须用 JdbcTemplate 直写 account_state 列,
 * 因为 AccountStateMapper.insert 不写该列(step1 导入态恒 NULL)。</p>
 */
class AccountMutationDbTest extends DbTestBase {

    @Autowired
    AccountService accountService;

    @Autowired
    AccountImportService importService;

    @Autowired
    AccountMapper accountMapper;

    @Autowired
    AccountGroupMapper accountGroupMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // ──────────────────────────── 工具方法 ────────────────────────────

    /**
     * 通过 AccountImportService 导入一条账号,返回其 account.id。
     * 导入后 account_state IS NULL(step1 态)。
     */
    private Long importOneAccount(String wsPhone) {
        String json = "[{\"wid\":\"" + wsPhone + "\","
                + "\"creds\":{\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}}]";
        var meta = new AccountImportDTO(null, 2, 1, 2, "印度", "test-batch-" + wsPhone, "r", null);
        AccountImportBatchVO batch = importService.importAccounts(meta, null, json);
        assertThat(batch.importedRows()).isEqualTo(1);

        Account a = accountMapper.selectActiveByWsPhone(wsPhone);
        assertThat(a).isNotNull();
        return a.getId();
    }

    /**
     * 用 JdbcTemplate 将 account_state 表中指定账号行的 account_state 列更新为指定值。
     * 用于构造 step1 导入后 Kafka 未上报时无法通过 Mapper 设置的状态。
     */
    private void setAccountState(Long accountId, int state) {
        jdbcTemplate.update(
                "UPDATE account_state SET account_state = ? WHERE account_id = ? AND tenant_id = ?",
                state, accountId, TEST_TENANT_ID
        );
    }

    /** 构建测试用账号分组并 insert,返回分组实体(含回填 id)。 */
    private AccountGroup insertGroup(String name) {
        AccountGroup g = new AccountGroup();
        g.setName(name);
        g.setRemark("test");
        g.setSystemBuiltin(0);
        long now = System.currentTimeMillis();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        accountGroupMapper.insert(g);
        assertThat(g.getId()).isNotNull();
        return g;
    }

    // ──────────────────────────── 删除:拒绝(state=NULL) ────────────────────────────

    /**
     * step1 导入的号 account_state=NULL(待上线)→ batchDelete 整批拒删抛 VALIDATION。
     */
    @Test
    void batchDelete_rejectsPendingOnlineAccount() {
        Long id = importOneAccount("8613800138010");
        // account_state IS NULL,不满足严格删除口径
        assertThatThrownBy(() -> accountService.batchDelete(List.of(id)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅导出/封禁/解绑");
    }

    // ──────────────────────────── 删除:通过(state=4 且 dispatched_at=NULL) ────────────────────────────

    /**
     * account_state=4(导出)且 dispatched_at IS NULL → batchDelete 成功软删。
     */
    @Test
    void batchDelete_allowsDeletableAccount_state4() {
        Long id = importOneAccount("8613800138020");
        // 构造 account_state=4(导出)
        setAccountState(id, 4);

        // 执行删除
        accountService.batchDelete(List.of(id));

        // 验 account 已软删(selectActiveByWsPhone 查不到)
        assertThat(accountMapper.selectActiveByWsPhone("8613800138020")).isNull();
    }

    // ──────────────────────────── 删除:混合批全或无 ────────────────────────────

    /**
     * I-1 混合批全或无:ids=[可删号(state=4), 不可删号(state=NULL)]
     * → batchDelete 整批抛 VALIDATION("仅导出/封禁/解绑..."),
     * 且可删的那条没被软删(selectActiveByWsPhone 仍非空)。
     */
    @Test
    void batchDelete_mixedBatch_rejectsAll() {
        Long deletableId = importOneAccount("8613800139001");
        setAccountState(deletableId, 4);            // 导出,可删
        Long pendingId   = importOneAccount("8613800139002"); // state=NULL,不可删

        assertThatThrownBy(() -> accountService.batchDelete(List.of(deletableId, pendingId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅导出/封禁/解绑");

        // 全或无:可删的那条也不应被软删
        assertThat(accountMapper.selectActiveByWsPhone("8613800139001")).isNotNull();
    }

    // ──────────────────────────── 迁移分组 ────────────────────────────

    /**
     * migrateGroup:将两条账号迁移到目标分组,account.account_group_id 应更新。
     */
    @Test
    void migrateGroup_updatesAccountGroupId() {
        Long id1 = importOneAccount("8613800138030");
        Long id2 = importOneAccount("8613800138031");

        AccountGroup targetGroup = insertGroup("测试迁移目标分组");

        long beforeMigrate = System.currentTimeMillis();
        accountService.migrateGroup(List.of(id1, id2), targetGroup.getId());

        // 验两条账号的 account_group_id 已更新
        List<Long> updatedIds = jdbcTemplate.query(
                "SELECT id FROM account WHERE id IN (?, ?) AND account_group_id = ? AND deleted_at IS NULL",
                (rs, n) -> rs.getLong("id"),
                id1, id2, targetGroup.getId()
        );
        assertThat(updatedIds).containsExactlyInAnyOrder(id1, id2);

        // M-1:updated_at 须在迁移后(即 >= beforeMigrate)
        Long updatedAt1 = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM account WHERE id = ?", Long.class, id1);
        assertThat(updatedAt1).isNotNull().isGreaterThanOrEqualTo(beforeMigrate);
    }

    // ──────────────────────────── 迁移分组:目标不存在 ────────────────────────────

    /**
     * migrateGroup 目标分组不存在 → 抛 NOT_FOUND(ErrorCode.NOT_FOUND.code() = 40401)。
     */
    @Test
    void migrateGroup_rejectsNonExistentGroup() {
        Long id = importOneAccount("8613800138040");
        assertThatThrownBy(() -> accountService.migrateGroup(List.of(id), 999_999_999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标分组不存在")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.code());
    }
}
