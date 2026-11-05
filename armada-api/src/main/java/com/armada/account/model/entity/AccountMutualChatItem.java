package com.armada.account.model.entity;

/** 分组互聊Item的持久事实，不存凭据。 */
public class AccountMutualChatItem {
    /** 定向操作ID。 */
    private Long id;
    /** 租户ID。 */
    private Long tenantId;
    /** 互聊任务ID。 */
    private Long taskId;
    /** 执行账号ID快照。 */
    private Long actorId;
    /** 目标账号ID快照。 */
    private Long targetId;
    /** 执行方WS号快照。 */
    private String actorPhone;
    /** 目标方WS号快照。 */
    private String targetPhone;
    /** 执行方协议句柄快照。 */
    private String protocolAccountId;
    /** 执行方协议WEB或ANDROID。 */
    private String protocolBackend;
    /** 1待发送2已提交3发送成功4失败5待确认6已取消。 */
    private Integer status;
    /** 当前命令ID，晚到结果按此关联。 */
    private String commandId;
    /** 当前派发次数。 */
    private Integer attemptNo;
    /** 1 为 A 到 B；2 为 B 到 A。 */
    private Integer direction;
    /** 协议确认发送后的消息标识。 */
    private String protocolMessageId;
    /** 结果原因码，不存协议原始敏感信息。 */
    private String reasonCode;
    /** 提交时间epoch毫秒。 */
    private Long submittedAt;
    /** 结果处理时间epoch毫秒。 */
    private Long resultAt;
    /** 同账号后继动作冷却水位。 */
    private Long nextExecuteAt;
    /** 创建时间epoch毫秒。 */
    private Long createdAt;
    /** 更新时间epoch毫秒。 */
    private Long updatedAt;
    /** 读取定向操作ID。 */
    public Long getId() {
        return id;
    }
    /** 设置定向操作ID。 */
    public void setId(Long value) {
        id = value;
    }
    /** 读取租户ID。 */
    public Long getTenantId() {
        return tenantId;
    }
    /** 设置租户ID。 */
    public void setTenantId(Long value) {
        tenantId = value;
    }
    /** 读取互聊任务ID。 */
    public Long getTaskId() {
        return taskId;
    }
    /** 设置互聊任务ID。 */
    public void setTaskId(Long value) {
        taskId = value;
    }
    /** 读取执行账号ID快照。 */
    public Long getActorId() {
        return actorId;
    }
    /** 设置执行账号ID快照。 */
    public void setActorId(Long value) {
        actorId = value;
    }
    /** 读取目标账号ID快照。 */
    public Long getTargetId() {
        return targetId;
    }
    /** 设置目标账号ID快照。 */
    public void setTargetId(Long value) {
        targetId = value;
    }
    /** 读取执行方WS号快照。 */
    public String getActorPhone() {
        return actorPhone;
    }
    /** 设置执行方WS号快照。 */
    public void setActorPhone(String value) {
        actorPhone = value;
    }
    /** 读取目标方WS号快照。 */
    public String getTargetPhone() {
        return targetPhone;
    }
    /** 设置目标方WS号快照。 */
    public void setTargetPhone(String value) {
        targetPhone = value;
    }
    /** 读取执行方协议句柄快照。 */
    public String getProtocolAccountId() {
        return protocolAccountId;
    }
    /** 设置执行方协议句柄快照。 */
    public void setProtocolAccountId(String value) {
        protocolAccountId = value;
    }
    /** 读取执行方协议WEB或ANDROID。 */
    public String getProtocolBackend() {
        return protocolBackend;
    }
    /** 设置执行方协议WEB或ANDROID。 */
    public void setProtocolBackend(String value) {
        protocolBackend = value;
    }
    /** 读取1待发送2已提交3发送成功4失败5待确认6已取消。 */
    public Integer getStatus() {
        return status;
    }
    /** 设置1待发送2已提交3发送成功4失败5待确认6已取消。 */
    public void setStatus(Integer value) {
        status = value;
    }
    /** 读取当前命令ID，晚到结果按此关联。 */
    public String getCommandId() {
        return commandId;
    }
    /** 设置当前命令ID，晚到结果按此关联。 */
    public void setCommandId(String value) {
        commandId = value;
    }
    /** 读取当前派发次数。 */
    public Integer getAttemptNo() {
        return attemptNo;
    }
    /** 设置当前派发次数。 */
    public void setAttemptNo(Integer value) {
        attemptNo = value;
    }
    /** 读取1为A向B发A文案2为B向A发B文案。 */
    public Integer getDirection() { return direction; }
    /** 设置1为A向B发A文案2为B向A发B文案。 */
    public void setDirection(Integer value) { direction = value; }
    /** 读取发送成功的协议消息ID。 */
    public String getProtocolMessageId() { return protocolMessageId; }
    /** 设置发送成功的协议消息ID。 */
    public void setProtocolMessageId(String value) { protocolMessageId = value; }
    /** 读取结果原因码，不存协议原始敏感信息。 */
    public String getReasonCode() {
        return reasonCode;
    }
    /** 设置结果原因码，不存协议原始敏感信息。 */
    public void setReasonCode(String value) {
        reasonCode = value;
    }
    /** 读取提交时间epoch毫秒。 */
    public Long getSubmittedAt() {
        return submittedAt;
    }
    /** 设置提交时间epoch毫秒。 */
    public void setSubmittedAt(Long value) {
        submittedAt = value;
    }
    /** 读取结果处理时间epoch毫秒。 */
    public Long getResultAt() {
        return resultAt;
    }
    /** 设置结果处理时间epoch毫秒。 */
    public void setResultAt(Long value) {
        resultAt = value;
    }
    /** 读取同账号后继动作冷却水位。 */
    public Long getNextExecuteAt() {
        return nextExecuteAt;
    }
    /** 设置同账号后继动作冷却水位。 */
    public void setNextExecuteAt(Long value) {
        nextExecuteAt = value;
    }
    /** 读取创建时间epoch毫秒。 */
    public Long getCreatedAt() {
        return createdAt;
    }
    /** 设置创建时间epoch毫秒。 */
    public void setCreatedAt(Long value) {
        createdAt = value;
    }
    /** 读取更新时间epoch毫秒。 */
    public Long getUpdatedAt() {
        return updatedAt;
    }
    /** 设置更新时间epoch毫秒。 */
    public void setUpdatedAt(Long value) {
        updatedAt = value;
    }
}
