package com.armada.task.model.entity;

/** 进群任务的管理员清理执行子记录，避免向主明细继续堆积后处理字段。 */
public class JoinTaskCleanup {
    /** 关联进群明细，子记录主键。 */
    private Long resultId;
    /** 返回关联进群明细，子记录主键。 */
    public Long getResultId() { return resultId; }
    /** 设置关联进群明细，子记录主键。 */
    public void setResultId(Long value) { resultId = value; }
    /** 租户标识。 */
    private Long tenantId;
    /** 返回租户标识。 */
    public Long getTenantId() { return tenantId; }
    /** 设置租户标识。 */
    public void setTenantId(Long value) { tenantId = value; }
    /** 所属任务。 */
    private Long joinTaskId;
    /** 返回所属任务。 */
    public Long getJoinTaskId() { return joinTaskId; }
    /** 设置所属任务。 */
    public void setJoinTaskId(Long value) { joinTaskId = value; }
    /** 真实群身份。 */
    private String groupJid;
    /** 返回真实群身份。 */
    public String getGroupJid() { return groupJid; }
    /** 设置真实群身份。 */
    public void setGroupJid(String value) { groupJid = value; }
    /** 清理阶段码，见 JoinTaskCleanupStatus。 */
    private int status;
    /** 返回清理阶段码，见 JoinTaskCleanupStatus。 */
    public int getStatus() { return status; }
    /** 设置清理阶段码，见 JoinTaskCleanupStatus。 */
    public void setStatus(int value) { status = value; }
    /** 固定清理名单及完成游标。 */
    private String contextJson;
    /** 返回固定清理名单及完成游标。 */
    public String getContextJson() { return contextJson; }
    /** 设置固定清理名单及完成游标。 */
    public void setContextJson(String value) { contextJson = value; }
    /** 失败阶段、目标和原因。 */
    private String reason;
    /** 返回失败阶段、目标和原因。 */
    public String getReason() { return reason; }
    /** 设置失败阶段、目标和原因。 */
    public void setReason(String value) { reason = value; }
    /** 下一次处理或在途调用截止时间。 */
    private Long nextExecuteAt;
    /** 返回下一次处理或在途调用截止时间。 */
    public Long getNextExecuteAt() { return nextExecuteAt; }
    /** 设置下一次处理或在途调用截止时间。 */
    public void setNextExecuteAt(Long value) { nextExecuteAt = value; }
    /** 在途群互斥键，终态清空。 */
    private String activeGroupJid;
    /** 返回在途群互斥键，终态清空。 */
    public String getActiveGroupJid() { return activeGroupJid; }
    /** 设置在途群互斥键，终态清空。 */
    public void setActiveGroupJid(String value) { activeGroupJid = value; }
    /** 最后修改时间。 */
    private Long updatedAt;
    /** 返回最后修改时间。 */
    public Long getUpdatedAt() { return updatedAt; }
    /** 设置最后修改时间。 */
    public void setUpdatedAt(Long value) { updatedAt = value; }
}
