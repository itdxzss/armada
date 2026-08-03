package com.armada.task.model.entity;

/** 执行行内的角色账号、在群状态与拉手占用，映射 {@code pull_task_group_account}。 */
public class PullTaskGroupAccount {

    /** 角色账号主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)。 */
    private Long taskId;

    /** 所属执行行 ID(→pull_task_group_execution.id)。 */
    private Long groupExecutionId;

    /** 账号 ID(→account.id)。 */
    private Long accountId;

    /** 账号号码展示快照。 */
    private String accountPhone;

    /** 角色，取值见 PullTaskGroupAccountRole。 */
    private Integer roleType;

    /** 同角色内顺序；人工补充时递增。 */
    private Integer roleSeq;

    /** 来源：1=初始选择 2=人工补充。 */
    private Integer sourceType;

    /** 选号方式：1=自动 2=手动。 */
    private Integer selectionMode;

    /** 进群方式：1=踩链接 2=管理员邀请 3=拉手拉入；站台补充为 null。 */
    private Integer entryMode;

    /** 在群状态，取值见 PullTaskGroupAccountMembershipStatus。 */
    private Integer membershipStatus;

    /** 确认在群时间(epoch 毫秒)。 */
    private Long joinedAt;

    /** 站台由哪次拉人调用拉入(→pull_task_pull_call.id)。 */
    private Long pullCallId;

    /** 群管理员权限状态：0=不适用 1=待设置 2=已提交 3=成功 4=失败 5=结果未知；仅管理角色有意义。 */
    private Integer adminStatus;

    /** 可用性，取值见 PullTaskGroupAccountAvailability。 */
    private Integer availabilityStatus;

    /** 不可用原因码。 */
    private String unavailableReasonCode;

    /** 风控冷却到期时间(epoch 毫秒)。 */
    private Long cooldownUntil;

    /** 拉手占用开始时间(epoch 毫秒)。 */
    private Long occupiedAt;

    /** 拉手占用释放时间(epoch 毫秒)；null 表示当前占用中。 */
    private Long releasedAt;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
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

    public Long getGroupExecutionId() {
        return groupExecutionId;
    }

    public void setGroupExecutionId(Long groupExecutionId) {
        this.groupExecutionId = groupExecutionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getAccountPhone() {
        return accountPhone;
    }

    public void setAccountPhone(String accountPhone) {
        this.accountPhone = accountPhone;
    }

    public Integer getRoleType() {
        return roleType;
    }

    public void setRoleType(Integer roleType) {
        this.roleType = roleType;
    }

    public Integer getRoleSeq() {
        return roleSeq;
    }

    public void setRoleSeq(Integer roleSeq) {
        this.roleSeq = roleSeq;
    }

    public Integer getSourceType() {
        return sourceType;
    }

    public void setSourceType(Integer sourceType) {
        this.sourceType = sourceType;
    }

    public Integer getSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(Integer selectionMode) {
        this.selectionMode = selectionMode;
    }

    public Integer getEntryMode() {
        return entryMode;
    }

    public void setEntryMode(Integer entryMode) {
        this.entryMode = entryMode;
    }

    public Integer getMembershipStatus() {
        return membershipStatus;
    }

    public void setMembershipStatus(Integer membershipStatus) {
        this.membershipStatus = membershipStatus;
    }

    public Long getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Long joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Long getPullCallId() {
        return pullCallId;
    }

    public void setPullCallId(Long pullCallId) {
        this.pullCallId = pullCallId;
    }

    public Integer getAdminStatus() {
        return adminStatus;
    }

    public void setAdminStatus(Integer adminStatus) {
        this.adminStatus = adminStatus;
    }

    public Integer getAvailabilityStatus() {
        return availabilityStatus;
    }

    public void setAvailabilityStatus(Integer availabilityStatus) {
        this.availabilityStatus = availabilityStatus;
    }

    public String getUnavailableReasonCode() {
        return unavailableReasonCode;
    }

    public void setUnavailableReasonCode(String unavailableReasonCode) {
        this.unavailableReasonCode = unavailableReasonCode;
    }

    public Long getCooldownUntil() {
        return cooldownUntil;
    }

    public void setCooldownUntil(Long cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    public Long getOccupiedAt() {
        return occupiedAt;
    }

    public void setOccupiedAt(Long occupiedAt) {
        this.occupiedAt = occupiedAt;
    }

    public Long getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Long releasedAt) {
        this.releasedAt = releasedAt;
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
