package com.armada.group.model.entity;

/** 每租户每群一行的群详情耐久同步任务实体。 */
public class GroupMetadataSyncTask {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 群入口 ID。 */
    private Long groupLinkId;

    /** 群入口归属用户 ID；跨租户调度查询从 group_link 派生，历史数据可为空。 */
    private Long ownerUserId;

    /** 调度查询解析出的 WhatsApp 群 JID；非任务表持久列。 */
    private String groupJid;

    /** 调度查询计算出的自建群邀请码必需标记；非任务表持久列。 */
    private Boolean inviteRequired;

    /** 调度查询读出的最近完整成员快照事实时间；非任务表持久列。 */
    private Long memberSnapshotAt;

    /** 任务状态稳定码。 */
    private Integer status;

    /** 最近触发来源稳定码。 */
    private Integer triggerSource;

    /** 已占用执行尝试次数。 */
    private Integer attemptCount;

    /** 下一次可执行时间(epoch 毫秒)。 */
    private Long nextRunAt;

    /** 当前运行租约到期时间(epoch 毫秒)。 */
    private Long leaseUntil;

    /** 当前执行账号 ID。 */
    private Long executionAccountId;

    /** 运行期间是否收到新触发。 */
    private Boolean rerunRequested;

    /** 当前等待结算的群快照命令 ID。 */
    private String currentCommandId;

    /** 当前命令请求 scope 位掩码；1=METADATA，2=INVITE_CODE。 */
    private Integer requestedScopeMask;

    /** 当前任务已成功落库 scope 位掩码。 */
    private Integer completedScopeMask;

    /** 已消费执行账号候选位置。 */
    private Integer candidateCursor;

    /** 当前命令结果超时水位(epoch 毫秒)。 */
    private Long resultDeadlineAt;

    /** 最近一次开始执行时间(epoch 毫秒)。 */
    private Long lastStartedAt;

    /** 最近一次成功时间(epoch 毫秒)。 */
    private Long lastSuccessAt;

    /** 最近一次错误码。 */
    private String lastErrorCode;

    /** 最近一次脱敏错误摘要。 */
    private String lastErrorMessage;

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

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public Boolean getInviteRequired() {
        return inviteRequired;
    }

    public void setInviteRequired(Boolean inviteRequired) {
        this.inviteRequired = inviteRequired;
    }

    public Long getMemberSnapshotAt() {
        return memberSnapshotAt;
    }

    public void setMemberSnapshotAt(Long memberSnapshotAt) {
        this.memberSnapshotAt = memberSnapshotAt;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(Integer triggerSource) {
        this.triggerSource = triggerSource;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Long getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Long nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public Long getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Long leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Long getExecutionAccountId() {
        return executionAccountId;
    }

    public void setExecutionAccountId(Long executionAccountId) {
        this.executionAccountId = executionAccountId;
    }

    public Boolean getRerunRequested() {
        return rerunRequested;
    }

    public void setRerunRequested(Boolean rerunRequested) {
        this.rerunRequested = rerunRequested;
    }

    public String getCurrentCommandId() {
        return currentCommandId;
    }

    public void setCurrentCommandId(String currentCommandId) {
        this.currentCommandId = currentCommandId;
    }

    public Integer getRequestedScopeMask() {
        return requestedScopeMask;
    }

    public void setRequestedScopeMask(Integer requestedScopeMask) {
        this.requestedScopeMask = requestedScopeMask;
    }

    public Integer getCompletedScopeMask() {
        return completedScopeMask;
    }

    public void setCompletedScopeMask(Integer completedScopeMask) {
        this.completedScopeMask = completedScopeMask;
    }

    public Integer getCandidateCursor() {
        return candidateCursor;
    }

    public void setCandidateCursor(Integer candidateCursor) {
        this.candidateCursor = candidateCursor;
    }

    public Long getResultDeadlineAt() {
        return resultDeadlineAt;
    }

    public void setResultDeadlineAt(Long resultDeadlineAt) {
        this.resultDeadlineAt = resultDeadlineAt;
    }

    public Long getLastStartedAt() {
        return lastStartedAt;
    }

    public void setLastStartedAt(Long lastStartedAt) {
        this.lastStartedAt = lastStartedAt;
    }

    public Long getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Long lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
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
