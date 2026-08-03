package com.armada.task.model.entity;

/** 群链接与 TXT 一对一冻结配对的执行行，映射 {@code pull_task_group_execution}。 */
public class PullTaskGroupExecution {

    /** 执行行主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 拉群任务 ID(→pull_task.id)；草稿期也非空。 */
    private Long taskId;

    /** 任务内展示与执行顺序。 */
    private Integer seq;

    /** 群入口 ID(→group_link.id)；最终创建时回填。 */
    private Long groupLinkId;

    /** 归一化群链接 chat.whatsapp.com/&lt;邀请码&gt;。 */
    private String normalizedLink;

    /** 群邀请码；大小写敏感。 */
    private String inviteCode;

    /** 粘贴内容中的原始行号。 */
    private Integer sourceLinkLineNo;

    /** WhatsApp 群 JID；启动校验时回填。 */
    private String groupJid;

    /** 上传 TXT 的序号。 */
    private Integer sourceFileIndex;

    /** TXT 原始文件名。 */
    private String sourceFileName;

    /** TXT 总行数。 */
    private Integer totalLineCount;

    /** 去重后有效料子数。 */
    private Integer validMemberCount;

    /** 非法号码行数。 */
    private Integer invalidLineCount;

    /** 文件内重复号码行数。 */
    private Integer duplicateLineCount;

    /** 执行状态，取值见 PullTaskExecutionStatus。 */
    private Integer executionStatus;

    /** 业务阶段，取值见 PullTaskExecutionStage。 */
    private Integer stage;

    /** 是否人工暂停：0 否 1 是；与资源等待独立。 */
    private Integer manualPaused;

    /** 资源等待类型，取值见 PullTaskWaitResourceType；非资源等待为 null。 */
    private Integer waitResourceType;

    /** 当前状态原因码。 */
    private String reasonCode;

    /** 当前状态原因描述(已脱敏)。 */
    private String reasonMessage;

    /** 管理账号轮询游标。 */
    private Integer nextManagerIndex;

    /** 拉手轮询游标。 */
    private Integer nextPullerIndex;

    /** 下次可调度时间(epoch 毫秒)；0 表示立即可调度。 */
    private Long nextRunAt;

    /** 抢占调度的实例标识。 */
    private String lockOwner;

    /** 调度锁过期时间(epoch 毫秒)；过期可被抢占回收。 */
    private Long lockExpiresAt;

    /** 执行行更新乐观锁版本号。 */
    private Integer version;

    /** 本行首次启动时间(epoch 毫秒)。 */
    private Long startedAt;

    /** 本行进入终态时间(epoch 毫秒)。 */
    private Long finishedAt;

    /** 最近一次真实业务动作时间(epoch 毫秒)。 */
    private Long lastBusinessExecutedAt;

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

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public String getNormalizedLink() {
        return normalizedLink;
    }

    public void setNormalizedLink(String normalizedLink) {
        this.normalizedLink = normalizedLink;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }

    public Integer getSourceLinkLineNo() {
        return sourceLinkLineNo;
    }

    public void setSourceLinkLineNo(Integer sourceLinkLineNo) {
        this.sourceLinkLineNo = sourceLinkLineNo;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public Integer getSourceFileIndex() {
        return sourceFileIndex;
    }

    public void setSourceFileIndex(Integer sourceFileIndex) {
        this.sourceFileIndex = sourceFileIndex;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public Integer getTotalLineCount() {
        return totalLineCount;
    }

    public void setTotalLineCount(Integer totalLineCount) {
        this.totalLineCount = totalLineCount;
    }

    public Integer getValidMemberCount() {
        return validMemberCount;
    }

    public void setValidMemberCount(Integer validMemberCount) {
        this.validMemberCount = validMemberCount;
    }

    public Integer getInvalidLineCount() {
        return invalidLineCount;
    }

    public void setInvalidLineCount(Integer invalidLineCount) {
        this.invalidLineCount = invalidLineCount;
    }

    public Integer getDuplicateLineCount() {
        return duplicateLineCount;
    }

    public void setDuplicateLineCount(Integer duplicateLineCount) {
        this.duplicateLineCount = duplicateLineCount;
    }

    public Integer getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(Integer executionStatus) {
        this.executionStatus = executionStatus;
    }

    public Integer getStage() {
        return stage;
    }

    public void setStage(Integer stage) {
        this.stage = stage;
    }

    public Integer getManualPaused() {
        return manualPaused;
    }

    public void setManualPaused(Integer manualPaused) {
        this.manualPaused = manualPaused;
    }

    public Integer getWaitResourceType() {
        return waitResourceType;
    }

    public void setWaitResourceType(Integer waitResourceType) {
        this.waitResourceType = waitResourceType;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getReasonMessage() {
        return reasonMessage;
    }

    public void setReasonMessage(String reasonMessage) {
        this.reasonMessage = reasonMessage;
    }

    public Integer getNextManagerIndex() {
        return nextManagerIndex;
    }

    public void setNextManagerIndex(Integer nextManagerIndex) {
        this.nextManagerIndex = nextManagerIndex;
    }

    public Integer getNextPullerIndex() {
        return nextPullerIndex;
    }

    public void setNextPullerIndex(Integer nextPullerIndex) {
        this.nextPullerIndex = nextPullerIndex;
    }

    public Long getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Long nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public String getLockOwner() {
        return lockOwner;
    }

    public void setLockOwner(String lockOwner) {
        this.lockOwner = lockOwner;
    }

    public Long getLockExpiresAt() {
        return lockExpiresAt;
    }

    public void setLockExpiresAt(Long lockExpiresAt) {
        this.lockExpiresAt = lockExpiresAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getLastBusinessExecutedAt() {
        return lastBusinessExecutedAt;
    }

    public void setLastBusinessExecutedAt(Long lastBusinessExecutedAt) {
        this.lastBusinessExecutedAt = lastBusinessExecutedAt;
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
