package com.armada.marketing.model.entity;

/**
 * 建群营销执行项实体,映射 group_creation_marketing_item。
 *
 * <p>一行表示一个料子文件与一个执行账号的匹配结果,由后台 worker 负责推进建群、营销发送和终态回写。</p>
 */
public class GroupCreationMarketingItem {

    /** 主键 ID。 */
    private Long id;

    /** 租户 ID,用于后台跨租户扫描后恢复租户上下文。 */
    private Long tenantId;

    /** 所属建群营销任务 ID。 */
    private Long taskId;

    /** 料子文件在创建请求中的原始顺序。 */
    private Integer fileIndex;

    /** 料子文件名快照。 */
    private String fileName;

    /** 规范化后的目标手机号文本,每行一个号码。 */
    private String materialContent;

    /** 料子中有效目标手机号数量。 */
    private Integer participantCount;

    /** 当前执行账号 ID;换号重试时会更新为新账号。 */
    private Long accountId;

    /** 当前执行账号手机号快照。 */
    private String accountPhone;

    /** 当前执行账号在协议层的账号 ID。 */
    private String protocolAccountId;

    /** 本次要创建的群名称。 */
    private String groupSubject;

    /** 建群成功后返回的群 JID。 */
    private String groupJid;

    /** 关联群库中的群链接 ID;建群营销新群未入库时为空。 */
    private Long groupLinkId;

    /** 联系人预保存和建群协议结果摘要 JSON。 */
    private String participantResultJson;

    /** 营销发送前读取到的群成员数量,用于导出统计。 */
    private Integer sendMemberCount;

    /** 群成员数量读取时间(epoch 毫秒)。 */
    private Long sendMemberCountCheckedAt;

    /** 换号重试历史 JSON,记录失败阶段、原因和已尝试账号。 */
    private String retryHistoryJson;

    /** 关联的普通营销任务 ID;直接走协议 outbox 发送时可为空。 */
    private Long marketingTaskId;

    /** 关联的普通营销目标 ID;直接走协议 outbox 发送时可为空。 */
    private Long marketingTargetId;

    /** 关联的普通营销发送尝试 ID;直接走协议 outbox 发送时可为空。 */
    private Long marketingAttemptId;

    /** 协议层营销消息命令 ID,用于异步发送结果回写。 */
    private String commandId;

    /** 执行项状态码,见 GroupCreationMarketingItemStatus。 */
    private Integer status;

    /** 失败或放弃原因码。 */
    private String reasonCode;

    /** 失败或放弃原因描述。 */
    private String reasonMessage;

    /** 下次可执行时间(epoch 毫秒),用于换号重试重新排队。 */
    private Long nextRunAt;

    /** 首次开始处理时间(epoch 毫秒)。 */
    private Long startedAt;

    /** 终态完成时间(epoch 毫秒)。 */
    private Long finishedAt;

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

    public Integer getFileIndex() {
        return fileIndex;
    }

    public void setFileIndex(Integer fileIndex) {
        this.fileIndex = fileIndex;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getMaterialContent() {
        return materialContent;
    }

    public void setMaterialContent(String materialContent) {
        this.materialContent = materialContent;
    }

    public Integer getParticipantCount() {
        return participantCount;
    }

    public void setParticipantCount(Integer participantCount) {
        this.participantCount = participantCount;
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

    public String getProtocolAccountId() {
        return protocolAccountId;
    }

    public void setProtocolAccountId(String protocolAccountId) {
        this.protocolAccountId = protocolAccountId;
    }

    public String getGroupSubject() {
        return groupSubject;
    }

    public void setGroupSubject(String groupSubject) {
        this.groupSubject = groupSubject;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public String getParticipantResultJson() {
        return participantResultJson;
    }

    public void setParticipantResultJson(String participantResultJson) {
        this.participantResultJson = participantResultJson;
    }

    public Integer getSendMemberCount() {
        return sendMemberCount;
    }

    public void setSendMemberCount(Integer sendMemberCount) {
        this.sendMemberCount = sendMemberCount;
    }

    public Long getSendMemberCountCheckedAt() {
        return sendMemberCountCheckedAt;
    }

    public void setSendMemberCountCheckedAt(Long sendMemberCountCheckedAt) {
        this.sendMemberCountCheckedAt = sendMemberCountCheckedAt;
    }

    public String getRetryHistoryJson() {
        return retryHistoryJson;
    }

    public void setRetryHistoryJson(String retryHistoryJson) {
        this.retryHistoryJson = retryHistoryJson;
    }

    public Long getMarketingTaskId() {
        return marketingTaskId;
    }

    public void setMarketingTaskId(Long marketingTaskId) {
        this.marketingTaskId = marketingTaskId;
    }

    public Long getMarketingTargetId() {
        return marketingTargetId;
    }

    public void setMarketingTargetId(Long marketingTargetId) {
        this.marketingTargetId = marketingTargetId;
    }

    public Long getMarketingAttemptId() {
        return marketingAttemptId;
    }

    public void setMarketingAttemptId(Long marketingAttemptId) {
        this.marketingAttemptId = marketingAttemptId;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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

    public Long getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Long nextRunAt) {
        this.nextRunAt = nextRunAt;
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
