package com.armada.contact.task.model.entity;

/** 通讯录营销任务账号维度读模型行，对应 contact_friend_task_account 表。 */
public class ContactFriendTaskAccount {

    /** 账号执行态：待执行。 */
    public static final String STATE_PENDING = "PENDING";
    /** 账号执行态：执行中。 */
    public static final String STATE_RUNNING = "RUNNING";
    /** 账号执行态：已完成。 */
    public static final String STATE_DONE = "DONE";
    /** 账号执行态：失败。 */
    public static final String STATE_FAILED = "FAILED";
    /** 账号执行态：跳过。 */
    public static final String STATE_SKIPPED = "SKIPPED";

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 所属任务 ID。 */
    private Long taskId;
    /** 发送账号 ID。 */
    private Long accountId;
    /** 账号号码快照。 */
    private String accountPhoneSnapshot;
    /** 账号状态快照：valid 有效 / invalid 无效。 */
    private String accountStatusSnapshot;
    /** 该账号计划发送条数。 */
    private Integer needSendNum;
    /** 该账号已成功条数。 */
    private Integer sentNum;
    /** 该账号失败条数。 */
    private Integer failNum;
    /** 账号执行态。 */
    private String state;
    /** 本任务使用的通讯录快照时间（epoch 毫秒）。 */
    private Long contactSyncedAt;
    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;
    /** 更新时间（epoch 毫秒）。 */
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

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getAccountPhoneSnapshot() {
        return accountPhoneSnapshot;
    }

    public void setAccountPhoneSnapshot(String accountPhoneSnapshot) {
        this.accountPhoneSnapshot = accountPhoneSnapshot;
    }

    public String getAccountStatusSnapshot() {
        return accountStatusSnapshot;
    }

    public void setAccountStatusSnapshot(String accountStatusSnapshot) {
        this.accountStatusSnapshot = accountStatusSnapshot;
    }

    public Integer getNeedSendNum() {
        return needSendNum;
    }

    public void setNeedSendNum(Integer needSendNum) {
        this.needSendNum = needSendNum;
    }

    public Integer getSentNum() {
        return sentNum;
    }

    public void setSentNum(Integer sentNum) {
        this.sentNum = sentNum;
    }

    public Integer getFailNum() {
        return failNum;
    }

    public void setFailNum(Integer failNum) {
        this.failNum = failNum;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Long getContactSyncedAt() {
        return contactSyncedAt;
    }

    public void setContactSyncedAt(Long contactSyncedAt) {
        this.contactSyncedAt = contactSyncedAt;
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
