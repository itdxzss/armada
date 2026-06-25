package com.armada.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountImportBatchMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.dto.AccountImportQuery;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.Account;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.account.service.AccountImportService;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨租户隔离 DbTest:钉死 TenantLineInnerInterceptor 行级隔离纪律。
 *
 * <p>BaseClass 的 @BeforeEach 已将租户设为 1L;本类在测试方法内切换上下文验证隔离语义。
 * 每个 @Test 在 @Transactional 内执行并回滚,互不干扰。</p>
 *
 * <p>三个场景:</p>
 * <ol>
 *   <li><b>列表隔离</b>:租户 1 插入账号 → 切租户 2 查不到 → 切回租户 1 查得到。</li>
 *   <li><b>跨租户同号允许</b>:租户 1 号码 X 导入成功 → 切租户 2 同号也能成功(uq 含 tenant_id)。</li>
 *   <li><b>删除/迁移隔离</b>:租户 2 对租户 1 的 account id 调 batchSoftDelete,因 tenant_id=2 注入,改不到租户 1 的行。</li>
 * </ol>
 */
class AccountTenantIsolationDbTest extends DbTestBase {

    @Autowired
    AccountMapper accountMapper;

    @Autowired
    AccountImportBatchMapper batchMapper;

    @Autowired
    AccountImportService importService;

    @Autowired
    JdbcTemplate jdbc;

    // ---- 辅助方法 ----

    /**
     * 在 当前 TenantContext 下通过 importService 导入一条账号并返回其 account.id。
     */
    private Long importOneAccount(String wsPhone) {
        String json = "[{\"wid\":\"" + wsPhone + "\","
                + "\"creds\":{\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}}]";
        var meta = new AccountImportDTO(null, 2, 1, 2, "印度", "ct-batch-" + wsPhone, "r", null);
        AccountImportBatchVO batch = importService.importAccounts(meta, null, json);
        assertThat(batch.importedRows()).as("导入应成功 1 行,wsPhone=%s", wsPhone).isEqualTo(1);
        Account a = accountMapper.selectActiveByWsPhone(wsPhone);
        assertThat(a).as("导入后应能查到账号,wsPhone=%s", wsPhone).isNotNull();
        return a.getId();
    }

    // ---- 测试用例 ----

    /**
     * 列表隔离:租户 1 下插入的账号,切到租户 2 后 countPage=0 且 selectPage 中找不到该号;
     * 切回租户 1 能查到。
     *
     * <p>覆盖 AccountMapper.countPage / selectPage 的 TenantLineInnerInterceptor 注入路径。</p>
     */
    @Test
    void listIsolation_tenant1AccountNotVisibleToTenant2() {
        // 租户 1 导入账号
        long now = System.currentTimeMillis();
        String wsPhone = "8619" + (now % 10000000L);
        Long accountId = importOneAccount(wsPhone);

        // 租户 1 下 selectActiveByWsPhone 应可见
        TenantContext.set(1L);
        Account seenByT1 = accountMapper.selectActiveByWsPhone(wsPhone);
        assertThat(seenByT1).as("租户 1 自己应能看到刚插入的账号").isNotNull();
        assertThat(seenByT1.getId()).isEqualTo(accountId);

        // 切到租户 2:selectActiveByWsPhone 应不可见(租户隔离拦截器注入 tenant_id=2)
        TenantContext.set(2L);
        try {
            Account seenByT2 = accountMapper.selectActiveByWsPhone(wsPhone);
            assertThat(seenByT2).as("租户 2 不应看到租户 1 的账号(tenant_id 隔离)").isNull();

            // countPage 在租户 2 下也查不到
            AccountQuery q = new AccountQuery();
            long countT2 = accountMapper.countPage(q);
            // 租户 2 下用 phone 精确过滤
            AccountQuery qPhone = new AccountQuery();
            qPhone.setPhone(wsPhone);
            long countT2Phone = accountMapper.countPage(qPhone);
            assertThat(countT2Phone).as("租户 2 按号码过滤应 count=0").isEqualTo(0);

            List<com.armada.account.model.vo.AccountListVoRow> pageT2 = accountMapper.selectPage(qPhone);
            assertThat(pageT2).as("租户 2 selectPage 不应返回租户 1 的账号").isEmpty();
        } finally {
            // 还原租户 1,保证后续 @AfterEach 及事务回滚正常
            TenantContext.set(1L);
        }
    }

    /**
     * 跨租户同号允许:租户 1 成功导入号码 X 后,切租户 2 导入同一号码也应成功
     * (uq_tenant_phone 约束含 tenant_id,跨租户不冲突);两租户各有独立一行。
     */
    @Test
    void samePhoneDifferentTenants_bothSucceed() {
        long now = System.currentTimeMillis();
        String sharedPhone = "8617" + (now % 10000000L);

        // 租户 1 导入
        TenantContext.set(1L);
        AccountImportBatchVO batchT1 = importService.importAccounts(
                new AccountImportDTO(null, 2, 1, 2, "印度", "ct-t1-" + sharedPhone, "r", null),
                null,
                "[{\"wid\":\"" + sharedPhone + "\","
                        + "\"creds\":{\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}}]"
        );
        assertThat(batchT1.importedRows()).as("租户 1 应成功导入 1 行").isEqualTo(1);

        // 切租户 2 导入同一号码
        TenantContext.set(2L);
        try {
            AccountImportBatchVO batchT2 = importService.importAccounts(
                    new AccountImportDTO(null, 2, 1, 2, "印度", "ct-t2-" + sharedPhone, "r", null),
                    null,
                    "[{\"wid\":\"" + sharedPhone + "\","
                            + "\"creds\":{\"registrationId\":2,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}}]"
            );
            assertThat(batchT2.importedRows()).as("租户 2 导入同号应成功(uq 含 tenant_id,跨租户允许同号)").isEqualTo(1);
            assertThat(batchT2.duplicateRows()).as("跨租户不应计重复").isEqualTo(0);

            // 两租户各自查到独立行
            Account acctT2 = accountMapper.selectActiveByWsPhone(sharedPhone);
            assertThat(acctT2).as("租户 2 应能查到自己的号").isNotNull();

            TenantContext.set(1L);
            Account acctT1 = accountMapper.selectActiveByWsPhone(sharedPhone);
            assertThat(acctT1).as("租户 1 应能查到自己的号").isNotNull();

            // 两行 tenant_id 不同(直接查库验)
            Long countBothTenants = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM account WHERE ws_phone = ? AND deleted_at IS NULL",
                    Long.class, sharedPhone);
            assertThat(countBothTenants).as("两租户各一行,合计应为 2").isEqualTo(2L);
        } finally {
            TenantContext.set(1L);
        }
    }

    /**
     * 删除/迁移隔离:租户 2 对租户 1 的 account id 调 batchSoftDelete,
     * 因拦截器注入 tenant_id=2,不命中租户 1 的行(返回 0 行);租户 1 切回查仍在。
     */
    @Test
    void softDelete_crossTenant_doesNotAffectOtherTenantRow() {
        long now = System.currentTimeMillis();
        String wsPhone = "8618" + (now % 10000000L);

        // 租户 1 导入
        TenantContext.set(1L);
        Long accountId = importOneAccount(wsPhone);

        // 切租户 2 尝试软删租户 1 的 id
        TenantContext.set(2L);
        try {
            int affected = accountMapper.batchSoftDelete(List.of(accountId), now);
            assertThat(affected).as("租户 2 软删租户 1 的行应命中 0 行(tenant_id 隔离)").isEqualTo(0);
        } finally {
            TenantContext.set(1L);
        }

        // 切回租户 1 验证账号仍在(deleted_at 仍 NULL)
        TenantContext.set(1L);
        Account still = accountMapper.selectActiveByWsPhone(wsPhone);
        assertThat(still).as("租户 2 无法删除租户 1 的账号,账号应仍在").isNotNull();
        assertThat(still.getId()).isEqualTo(accountId);
    }
}
