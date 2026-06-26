package com.armada.task.model.entity;

/**
 * 进群任务配置实体，映射 join_task 表一行。
 * 裸 POJO + 手写 getter/setter（无 Lombok），Mapper 直出。
 * 时间列为 BIGINT epoch 毫秒，insert 时由应用层显式传入。
 */
public class JoinTask {

    /** 主键。 */
    private Long id;

    /** 租户 ID（拦截器注入，不手写 SQL）。 */
    private Long tenantId;

    /** 任务名称。 */
    private String name;

    /** 选中账号分组 ID（JSON 数组快照）。 */
    private String accountGroupIds;

    /** 账号分组名快照（展示用，/ 连接，免 JOIN）。 */
    private String accountGroupNames;

    /** 选中账号 ID（JSON 数组快照，编辑回填 + 解析号码）。 */
    private String selectedAccountIds;

    /** 进群链接输入框原始文本（编辑回填，不去重/拆行）。 */
    private String linksText;

    /** 分配方式：FIXED_ACCOUNTS_PER_LINK 每链接固定账号数 / FIXED_ACCOUNT_MULTI_LINK 固定账号多链接。 */
    private String distributionMode;

    /** 方式一：每条群链接分配账号数。 */
    private int accountsPerLink;

    /** 方式二：参与执行账号数。 */
    private int executorAccountCount;

    /** 方式二：每账号进群链接数。 */
    private int linksPerAccount;

    /** 方式一进群间隔下限（秒）。 */
    private int fixedIntervalMinSec;

    /** 方式一进群间隔上限（秒）。 */
    private int fixedIntervalMaxSec;

    /** 方式二进群间隔下限（秒）。 */
    private int multiIntervalMinSec;

    /** 方式二进群间隔上限（秒）。 */
    private int multiIntervalMaxSec;

    /** 进群间隔展示（如 10-20s），筛选下拉去重源。 */
    private String intervalLabel;

    /** 失败是否自动重试。 */
    private boolean retryEnabled;

    /** 重试次数上限。 */
    private int retryLimit;

    /** 失败处理策略快照（JSON/标签，编辑回填）。 */
    private String failurePolicy;

    /** 计划进群次数（按分配方式推算）。 */
    private int total;

    /** 已执行次数（引擎回写，建时 0）。 */
    private int executed;

    /** 成功进群数（引擎回写，建时 0）。 */
    private int success;

    /** 失败数（引擎回写，建时 0）。 */
    private int failed;

    /** 待执行数（建时 = total）。 */
    private int pending;

    /** 状态码：DRAFT/RUNNING/PAUSED/STOPPED/DONE/FAILED。中文展示由前端转换。 */
    private String status;

    /** 创建人 user_id（操作员；展示名后续 JOIN 解析）。 */
    private Long createdBy;

    /** 创建时间（epoch 毫秒，应用层写）。 */
    private Long createdAt;

    /** 更新时间（epoch 毫秒，应用层写）。 */
    private Long updatedAt;

    /** 软删时间（epoch 毫秒）；NULL = 有效。 */
    private Long deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAccountGroupIds() {
        return accountGroupIds;
    }

    public void setAccountGroupIds(String accountGroupIds) {
        this.accountGroupIds = accountGroupIds;
    }

    public String getAccountGroupNames() {
        return accountGroupNames;
    }

    public void setAccountGroupNames(String accountGroupNames) {
        this.accountGroupNames = accountGroupNames;
    }

    public String getSelectedAccountIds() {
        return selectedAccountIds;
    }

    public void setSelectedAccountIds(String selectedAccountIds) {
        this.selectedAccountIds = selectedAccountIds;
    }

    public String getLinksText() {
        return linksText;
    }

    public void setLinksText(String linksText) {
        this.linksText = linksText;
    }

    public String getDistributionMode() {
        return distributionMode;
    }

    public void setDistributionMode(String distributionMode) {
        this.distributionMode = distributionMode;
    }

    public int getAccountsPerLink() {
        return accountsPerLink;
    }

    public void setAccountsPerLink(int accountsPerLink) {
        this.accountsPerLink = accountsPerLink;
    }

    public int getExecutorAccountCount() {
        return executorAccountCount;
    }

    public void setExecutorAccountCount(int executorAccountCount) {
        this.executorAccountCount = executorAccountCount;
    }

    public int getLinksPerAccount() {
        return linksPerAccount;
    }

    public void setLinksPerAccount(int linksPerAccount) {
        this.linksPerAccount = linksPerAccount;
    }

    public int getFixedIntervalMinSec() {
        return fixedIntervalMinSec;
    }

    public void setFixedIntervalMinSec(int fixedIntervalMinSec) {
        this.fixedIntervalMinSec = fixedIntervalMinSec;
    }

    public int getFixedIntervalMaxSec() {
        return fixedIntervalMaxSec;
    }

    public void setFixedIntervalMaxSec(int fixedIntervalMaxSec) {
        this.fixedIntervalMaxSec = fixedIntervalMaxSec;
    }

    public int getMultiIntervalMinSec() {
        return multiIntervalMinSec;
    }

    public void setMultiIntervalMinSec(int multiIntervalMinSec) {
        this.multiIntervalMinSec = multiIntervalMinSec;
    }

    public int getMultiIntervalMaxSec() {
        return multiIntervalMaxSec;
    }

    public void setMultiIntervalMaxSec(int multiIntervalMaxSec) {
        this.multiIntervalMaxSec = multiIntervalMaxSec;
    }

    public String getIntervalLabel() {
        return intervalLabel;
    }

    public void setIntervalLabel(String intervalLabel) {
        this.intervalLabel = intervalLabel;
    }

    public boolean isRetryEnabled() {
        return retryEnabled;
    }

    public void setRetryEnabled(boolean retryEnabled) {
        this.retryEnabled = retryEnabled;
    }

    public int getRetryLimit() {
        return retryLimit;
    }

    public void setRetryLimit(int retryLimit) {
        this.retryLimit = retryLimit;
    }

    public String getFailurePolicy() {
        return failurePolicy;
    }

    public void setFailurePolicy(String failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getExecuted() {
        return executed;
    }

    public void setExecuted(int executed) {
        this.executed = executed;
    }

    public int getSuccess() {
        return success;
    }

    public void setSuccess(int success) {
        this.success = success;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public int getPending() {
        return pending;
    }

    public void setPending(int pending) {
        this.pending = pending;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
