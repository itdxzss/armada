package com.armada.task.mapper;

import com.armada.task.model.dto.JoinTaskFilter;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JoinTaskMapper 真库测试：验证 insert/selectByTenantAndId/countPage/selectPage/
 * selectDistinctIntervals/update/batchSoftDelete 全路径（含筛选、分页、幂等）。
 *
 * <p>每个 @Test 在 @Transactional 内执行并回滚，数据互不干扰。</p>
 */
class JoinTaskMapperDbTest extends DbTestBase {

    @Autowired
    JoinTaskMapper mapper;

    @Autowired
    JoinTaskResultMapper resultMapper;

    /** 构造一条最小合法的进群任务实体，时间使用调用方传入的 epoch。 */
    private JoinTask buildTask(String name, String status, String distributionMode,
                               String intervalLabel, long createdAt) {
        JoinTask t = new JoinTask();
        t.setName(name);
        t.setAccountGroupIds("[1,2]");
        t.setAccountGroupNames("分组A/分组B");
        t.setSelectedAccountIds("[101,102]");
        t.setLinksText("https://chat.whatsapp.com/abc123\nhttps://chat.whatsapp.com/def456");
        t.setDistributionMode(distributionMode);
        t.setAccountsPerLink(5);
        t.setExecutorAccountCount(0);
        t.setLinksPerAccount(0);
        t.setFixedIntervalMinSec(10);
        t.setFixedIntervalMaxSec(20);
        t.setMultiIntervalMinSec(0);
        t.setMultiIntervalMaxSec(0);
        t.setIntervalLabel(intervalLabel);
        t.setRetryEnabled(true);
        t.setRetryLimit(3);
        t.setFailurePolicy("SKIP");
        t.setTotal(10);
        t.setExecuted(0);
        t.setSuccess(0);
        t.setFailed(0);
        t.setPending(10);
        t.setStatus(status);
        t.setCreatedBy(1001L);
        t.setCreatedAt(createdAt);
        t.setUpdatedAt(createdAt);
        return t;
    }

    // ─── 用例 1：insert + selectByTenantAndId 字段一致性 ────────────────────────

    @Test
    void insert_then_selectByTenantAndId_roundTrip() {
        long now = System.currentTimeMillis();
        JoinTask t = buildTask("测试任务A", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "10-20s", now);

        int rows = mapper.insert(t);
        assertThat(rows).isEqualTo(1);
        assertThat(t.getId()).isNotNull().isPositive();

        JoinTask got = mapper.selectByTenantAndId(t.getId());
        assertThat(got).isNotNull();
        assertThat(got.getName()).isEqualTo("测试任务A");
        assertThat(got.getStatus()).isEqualTo("DRAFT");
        assertThat(got.getAccountGroupIds()).isEqualTo("[1,2]");
        assertThat(got.getAccountGroupNames()).isEqualTo("分组A/分组B");
        assertThat(got.getSelectedAccountIds()).isEqualTo("[101,102]");
        assertThat(got.getLinksText()).contains("abc123");
        assertThat(got.getDistributionMode()).isEqualTo("FIXED_ACCOUNTS_PER_LINK");
        assertThat(got.getAccountsPerLink()).isEqualTo(5);
        assertThat(got.getIntervalLabel()).isEqualTo("10-20s");
        assertThat(got.isRetryEnabled()).isTrue();
        assertThat(got.getRetryLimit()).isEqualTo(3);
        assertThat(got.getFailurePolicy()).isEqualTo("SKIP");
        assertThat(got.getTotal()).isEqualTo(10);
        assertThat(got.getPending()).isEqualTo(10);
        assertThat(got.getExecuted()).isEqualTo(0);
        assertThat(got.getSuccess()).isEqualTo(0);
        assertThat(got.getFailed()).isEqualTo(0);
        assertThat(got.getCreatedBy()).isEqualTo(1001L);
        assertThat(got.getCreatedAt()).isEqualTo(now);
        assertThat(got.getUpdatedAt()).isEqualTo(now);
        assertThat(got.getDeletedAt()).isNull();
    }

    // ─── 用例 2：selectPage / countPage 多维度筛选 + 分页 ─────────────────────

    @Test
    void selectPage_countPage_filterAndPagination() {
        long base = System.currentTimeMillis();
        // t1: DRAFT + FIXED_ACCOUNTS_PER_LINK + 10-20s，较早
        JoinTask t1 = buildTask("进群任务Alpha", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "10-20s", base);
        // t2: RUNNING + FIXED_ACCOUNT_MULTI_LINK + 5-10s，居中
        JoinTask t2 = buildTask("进群任务Beta", "RUNNING", "FIXED_ACCOUNT_MULTI_LINK", "5-10s", base + 1000);
        // t3: DRAFT + FIXED_ACCOUNTS_PER_LINK + 10-20s，最晚；links_text 含特殊关键字
        JoinTask t3 = buildTask("进群任务Gamma", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "10-20s", base + 2000);
        t3.setLinksText("https://chat.whatsapp.com/searchme");
        mapper.insert(t1);
        mapper.insert(t2);
        mapper.insert(t3);

        // 2a. keyword 命中 name（Alpha）
        JoinTaskFilter byNameKw = new JoinTaskFilter("Alpha", null, null, null, null, null, null);
        assertThat(mapper.countPage(byNameKw)).isEqualTo(1);
        List<JoinTask> byNameResult = mapper.selectPage(byNameKw, 0, 10);
        assertThat(byNameResult).hasSize(1);
        assertThat(byNameResult.get(0).getName()).isEqualTo("进群任务Alpha");

        // 2b. keyword 命中 links_text（searchme → t3）
        JoinTaskFilter byLinkKw = new JoinTaskFilter("searchme", null, null, null, null, null, null);
        assertThat(mapper.countPage(byLinkKw)).isEqualTo(1);
        assertThat(mapper.selectPage(byLinkKw, 0, 10).get(0).getName()).isEqualTo("进群任务Gamma");

        // 2c. status = RUNNING → 只有 t2
        JoinTaskFilter byStatus = new JoinTaskFilter(null, "RUNNING", null, null, null, null, null);
        assertThat(mapper.countPage(byStatus)).isEqualTo(1);
        assertThat(mapper.selectPage(byStatus, 0, 10).get(0).getName()).isEqualTo("进群任务Beta");

        // 2d. distributionMode = FIXED_ACCOUNT_MULTI_LINK → 只有 t2
        JoinTaskFilter byMode = new JoinTaskFilter(null, null, null, "FIXED_ACCOUNT_MULTI_LINK", null, null, null);
        assertThat(mapper.countPage(byMode)).isEqualTo(1);

        // 2e. interval = "10-20s" → t1 + t3
        JoinTaskFilter byInterval = new JoinTaskFilter(null, null, null, null, "10-20s", null, null);
        assertThat(mapper.countPage(byInterval)).isEqualTo(2);

        // 2f. dateFrom/dateTo 区间：只含 t2 + t3（base+500 ~ base+3000）
        JoinTaskFilter byDate = new JoinTaskFilter(null, null, null, null, null, base + 500L, base + 3000L);
        assertThat(mapper.countPage(byDate)).isEqualTo(2);
        List<JoinTask> byDateList = mapper.selectPage(byDate, 0, 10);
        assertThat(byDateList).hasSize(2);
        // ORDER BY id DESC → t3（后插入 id 大）在前
        assertThat(byDateList.get(0).getName()).isEqualTo("进群任务Gamma");

        // 2g. 分页：每页 2，offset=0 取恰好 2 条（LIMIT 生效），按 id DESC
        JoinTaskFilter all = new JoinTaskFilter(null, null, null, null, null, null, null);
        assertThat(mapper.countPage(all)).isEqualTo(3);
        List<JoinTask> page0 = mapper.selectPage(all, 0, 2);
        assertThat(page0).hasSize(2);
        assertThat(page0.get(0).getId()).isGreaterThan(page0.get(1).getId()); // ORDER BY id DESC
    }

    // ─── 用例 3：selectDistinctIntervals 去重 + 不含空串 ─────────────────────

    @Test
    void selectDistinctIntervals_deduplicatesAndExcludesEmpty() {
        long now = System.currentTimeMillis();
        // 插 3 条：两条 "10-20s"，一条 "5-10s"，一条空串
        mapper.insert(buildTask("T1", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "10-20s", now));
        mapper.insert(buildTask("T2", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "10-20s", now + 1));
        mapper.insert(buildTask("T3", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "5-10s", now + 2));
        JoinTask empty = buildTask("T4", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "", now + 3);
        mapper.insert(empty);

        List<String> intervals = mapper.selectDistinctIntervals();
        assertThat(intervals).containsExactlyInAnyOrder("10-20s", "5-10s");
        assertThat(intervals).doesNotContain("");
    }

    // ─── 用例 4：update 字段覆盖 + 软删行不被更新 ────────────────────────────

    @Test
    void update_modifiesConfigAndCounters_softDeletedRowIgnored() {
        long now = System.currentTimeMillis();
        JoinTask t = buildTask("原始任务", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "10-20s", now);
        mapper.insert(t);

        // 修改名称 + 配置列 + total/pending + updated_at
        t.setName("修改后任务");
        t.setDistributionMode("FIXED_ACCOUNT_MULTI_LINK");
        t.setAccountsPerLink(8);
        t.setIntervalLabel("30-60s");
        t.setTotal(20);
        t.setPending(15);
        t.setUpdatedAt(now + 5000);

        int affected = mapper.update(t);
        assertThat(affected).isEqualTo(1);

        JoinTask updated = mapper.selectByTenantAndId(t.getId());
        assertThat(updated.getName()).isEqualTo("修改后任务");
        assertThat(updated.getDistributionMode()).isEqualTo("FIXED_ACCOUNT_MULTI_LINK");
        assertThat(updated.getAccountsPerLink()).isEqualTo(8);
        assertThat(updated.getIntervalLabel()).isEqualTo("30-60s");
        assertThat(updated.getTotal()).isEqualTo(20);
        assertThat(updated.getPending()).isEqualTo(15);
        assertThat(updated.getUpdatedAt()).isEqualTo(now + 5000);

        // 软删后 update 不命中
        long delAt = now + 10000;
        mapper.batchSoftDelete(List.of(t.getId()), delAt);
        t.setName("不应写入");
        int affectedAfterDelete = mapper.update(t);
        assertThat(affectedAfterDelete).isEqualTo(0);
    }

    // ─── 用例 5：batchSoftDelete 幂等 + selectByTenantAndId 返回 null ─────────

    @Test
    void batchSoftDelete_idempotent_and_selectReturnsNull() {
        long now = System.currentTimeMillis();
        JoinTask t1 = buildTask("软删任务A", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "10-20s", now);
        JoinTask t2 = buildTask("软删任务B", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "10-20s", now + 1);
        mapper.insert(t1);
        mapper.insert(t2);

        long delAt = now + 9999;
        // 首次软删 2 条，返回 2
        int deleted = mapper.batchSoftDelete(List.of(t1.getId(), t2.getId()), delAt);
        assertThat(deleted).isEqualTo(2);

        // 再删同 ids → 幂等，返回 0
        int idempotent = mapper.batchSoftDelete(List.of(t1.getId(), t2.getId()), delAt + 1);
        assertThat(idempotent).isEqualTo(0);

        // selectByTenantAndId 返回 null
        assertThat(mapper.selectByTenantAndId(t1.getId())).isNull();
        assertThat(mapper.selectByTenantAndId(t2.getId())).isNull();
    }

    @Test
    void updateTaskStatusAndRefreshCounters_recomputesFromExecutableRowsOnly() {
        long now = System.currentTimeMillis();
        JoinTask task = buildTask("执行任务", "DRAFT", "FIXED_ACCOUNTS_PER_LINK", "10-20s", now);
        task.setTotal(3);
        task.setExecuted(0);
        task.setSuccess(0);
        task.setFailed(0);
        task.setPending(3);
        mapper.insert(task);

        resultMapper.insertResults(List.of(
                result(task.getId(), "8616001", 701L, "SUCCESS", ""),
                result(task.getId(), "8616002", 702L, "FAILED", "JOIN_FAILED"),
                result(task.getId(), "8616003", 703L, "PENDING", ""),
                result(task.getId(), "8616004", null, "FAILED", "INVALID_LINK")
        ));

        long updatedAt = now + 10_000;
        assertThat(mapper.updateTaskStatus(task.getId(), "RUNNING", updatedAt)).isEqualTo(1);
        assertThat(mapper.refreshCounters(task.getId())).isEqualTo(1);

        JoinTask updated = mapper.selectByTenantAndId(task.getId());
        assertThat(updated.getStatus()).isEqualTo("RUNNING");
        assertThat(updated.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(updated.getTotal()).isEqualTo(3);
        assertThat(updated.getExecuted()).isEqualTo(2);
        assertThat(updated.getSuccess()).isEqualTo(1);
        assertThat(updated.getFailed()).isEqualTo(1);
        assertThat(updated.getPending()).isEqualTo(1);
    }

    private static JoinTaskResult result(Long taskId, String account, Long accountId, String status, String reason) {
        long now = System.currentTimeMillis();
        JoinTaskResult result = new JoinTaskResult();
        result.setJoinTaskId(taskId);
        result.setAccount(account);
        result.setAccountId(accountId);
        result.setLink("https://chat.whatsapp.com/" + account);
        result.setStatus(status);
        result.setReason(reason);
        result.setCreatedAt(now);
        result.setUpdatedAt(now);
        return result;
    }
}
