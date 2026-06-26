package com.armada.task.model.entity;

/**
 * 进群任务明细实体，映射 join_task_result 表一行。
 * 每账号每链接对应一行计划与执行结果。
 * 裸 POJO + 手写 getter/setter（无 Lombok），Mapper 直出。
 * 时间列为 BIGINT epoch 毫秒，引擎逐行回写。
 */
public class JoinTaskResult {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 关联的进群任务 ID（→ join_task.id）。 */
    private Long joinTaskId;

    /** 执行账号号码/别名（快照，展示用）。 */
    private String account;

    /** 执行账号 ID（→ account.id；建任务时回填，可空）。 */
    private Long accountId;

    /** 进群链接。 */
    private String link;

    /** 进群结果码：PENDING/SUCCESS/FAILED。中文展示由前端转换。 */
    private String status;

    /** 失败原因（无效链接行建时即写）。 */
    private String reason;

    /** 进群成功后回填群 JID（Kafka promote 匹配）。 */
    private String groupJid;

    /** 是否已成管理员（Kafka participant_changed promote 回写）。 */
    private boolean isAdmin;

    /** 成为管理员时间（epoch 毫秒）；可空。 */
    private Long promotedAt;

    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;

    /** 更新时间（epoch 毫秒，引擎逐行回写）。 */
    private Long updatedAt;

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

    public Long getJoinTaskId() {
        return joinTaskId;
    }

    public void setJoinTaskId(Long joinTaskId) {
        this.joinTaskId = joinTaskId;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public boolean isAdmin() {
        return isAdmin;
    }

    public void setAdmin(boolean admin) {
        isAdmin = admin;
    }

    public Long getPromotedAt() {
        return promotedAt;
    }

    public void setPromotedAt(Long promotedAt) {
        this.promotedAt = promotedAt;
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
}
