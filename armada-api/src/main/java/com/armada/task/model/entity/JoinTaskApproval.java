package com.armada.task.model.entity;

/** 仅待审核明细创建的恢复子记录；不复用入群后提权字段。 */
public class JoinTaskApproval {
    /** 所属进群明细。 */
    private Long resultId;
    /** 所属租户。 */
    private Long tenantId;
    /** 所属任务。 */
    private Long joinTaskId;
    /** 处理阶段。 */
    private int stage;
    /** 当前阶段已领取。 */
    private boolean inFlight;
    /** 领取版本，拒绝旧结果。 */
    private long version;
    /** 本次邀请确认的群身份。 */
    private String groupJid;
    /** 关闭审核的原群管理员。 */
    private Long actorAccountId;
    /** 原管理员身份快照。 */
    private String actorPhone;
    /** 待入群账号身份快照。 */
    private String targetPhone;
    /** 待入群账号协议身份。 */
    private String targetProtocolAccountId;
    /** 本条申请的协议成员标识。 */
    private String pendingJid;
    /** 当前阶段说明或失败原因。 */
    private String reason;
    /** 下次执行或租约截止时间。 */
    private Long nextExecuteAt;
    /** 本条自动处理总截止时间。 */
    private long deadlineAt;
    /** 更新时间。 */
    private long updatedAt;
    /** 读取所属进群明细。 */
    public Long getResultId() { return resultId; }
    /** 保存所属进群明细。 */
    public void setResultId(Long value) { this.resultId = value; }
    /** 读取所属租户。 */
    public Long getTenantId() { return tenantId; }
    /** 保存所属租户。 */
    public void setTenantId(Long value) { this.tenantId = value; }
    /** 读取所属任务。 */
    public Long getJoinTaskId() { return joinTaskId; }
    /** 保存所属任务。 */
    public void setJoinTaskId(Long value) { this.joinTaskId = value; }
    /** 读取处理阶段。 */
    public int getStage() { return stage; }
    /** 保存处理阶段。 */
    public void setStage(int value) { this.stage = value; }
    /** 读取当前阶段已领取。 */
    public boolean isInFlight() { return inFlight; }
    /** 保存当前阶段已领取。 */
    public void setInFlight(boolean value) { this.inFlight = value; }
    /** 读取领取版本，拒绝旧结果。 */
    public long getVersion() { return version; }
    /** 保存领取版本，拒绝旧结果。 */
    public void setVersion(long value) { this.version = value; }
    /** 读取本次邀请确认的群身份。 */
    public String getGroupJid() { return groupJid; }
    /** 保存本次邀请确认的群身份。 */
    public void setGroupJid(String value) { this.groupJid = value; }
    /** 读取关闭审核的原群管理员。 */
    public Long getActorAccountId() { return actorAccountId; }
    /** 保存关闭审核的原群管理员。 */
    public void setActorAccountId(Long value) { this.actorAccountId = value; }
    /** 读取原管理员身份快照。 */
    public String getActorPhone() { return actorPhone; }
    /** 保存原管理员身份快照。 */
    public void setActorPhone(String value) { this.actorPhone = value; }
    /** 读取待入群账号身份快照。 */
    public String getTargetPhone() { return targetPhone; }
    /** 保存待入群账号身份快照。 */
    public void setTargetPhone(String value) { this.targetPhone = value; }
    /** 读取待入群账号协议身份。 */
    public String getTargetProtocolAccountId() { return targetProtocolAccountId; }
    /** 保存待入群账号协议身份。 */
    public void setTargetProtocolAccountId(String value) { this.targetProtocolAccountId = value; }
    /** 读取本条申请的协议成员标识。 */
    public String getPendingJid() { return pendingJid; }
    /** 保存本条申请的协议成员标识。 */
    public void setPendingJid(String value) { this.pendingJid = value; }
    /** 读取当前阶段说明或失败原因。 */
    public String getReason() { return reason; }
    /** 保存当前阶段说明或失败原因。 */
    public void setReason(String value) { this.reason = value; }
    /** 读取下次执行或租约截止时间。 */
    public Long getNextExecuteAt() { return nextExecuteAt; }
    /** 保存下次执行或租约截止时间。 */
    public void setNextExecuteAt(Long value) { this.nextExecuteAt = value; }
    /** 读取本条自动处理总截止时间。 */
    public long getDeadlineAt() { return deadlineAt; }
    /** 保存本条自动处理总截止时间。 */
    public void setDeadlineAt(long value) { this.deadlineAt = value; }
    /** 读取更新时间。 */
    public long getUpdatedAt() { return updatedAt; }
    /** 保存更新时间。 */
    public void setUpdatedAt(long value) { this.updatedAt = value; }
}
