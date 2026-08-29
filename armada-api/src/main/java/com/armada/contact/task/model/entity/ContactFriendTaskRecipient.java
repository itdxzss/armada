package com.armada.contact.task.model.entity;

/**
 * 通讯录营销任务收件人明细行，对应 contact_friend_task_recipient 表。
 *
 * <p>号码与 JID 都是<b>展开时的快照</b>，不外键 {@code account_contact}——通讯录会变，
 * 任务事实不能跟着漂（超链一期 §6.6 既有结论）。</p>
 */
public class ContactFriendTaskRecipient {

    /** 发送状态：待发送。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 发送状态：已投递协议层，等待回执。 */
    public static final String STATUS_SENDING = "SENDING";
    /** 发送状态：成功送达。 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 发送状态：终态失败。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 主键。 */
    private Long id;
    /** 租户 ID。 */
    private Long tenantId;
    /** 所属任务 ID。 */
    private Long taskId;
    /** 所属任务账号行 ID。 */
    private Long taskAccountId;
    /** 联系人号码快照，不带加号的纯数字。 */
    private String contactPhone;
    /** 联系人 JID 快照。 */
    private String contactJid;
    /** 展开时该联系人是否有名字。 */
    private Integer contactNamed;
    /** 发送状态。 */
    private String sendStatus;
    /** 已尝试次数。 */
    private Integer attemptCount;
    /** 协议返回的消息 ID。 */
    private String protocolMessageId;
    /** 失败错误码。 */
    private String errorCode;
    /** 失败描述。 */
    private String errorDesc;
    /** 首次发出时间（epoch 毫秒）。 */
    private Long firstSentAt;
    /** 最近一次尝试时间（epoch 毫秒）。 */
    private Long lastAttemptAt;
    /** 最近一次投递所属轮次号。 */
    private Long roundNo;
    /** 最近一次投递的协议命令 ID。 */
    private String commandId;
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

    public Long getTaskAccountId() {
        return taskAccountId;
    }

    public void setTaskAccountId(Long taskAccountId) {
        this.taskAccountId = taskAccountId;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getContactJid() {
        return contactJid;
    }

    public void setContactJid(String contactJid) {
        this.contactJid = contactJid;
    }

    public Integer getContactNamed() {
        return contactNamed;
    }

    public void setContactNamed(Integer contactNamed) {
        this.contactNamed = contactNamed;
    }

    public String getSendStatus() {
        return sendStatus;
    }

    public void setSendStatus(String sendStatus) {
        this.sendStatus = sendStatus;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getProtocolMessageId() {
        return protocolMessageId;
    }

    public void setProtocolMessageId(String protocolMessageId) {
        this.protocolMessageId = protocolMessageId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorDesc() {
        return errorDesc;
    }

    public void setErrorDesc(String errorDesc) {
        this.errorDesc = errorDesc;
    }

    public Long getFirstSentAt() {
        return firstSentAt;
    }

    public void setFirstSentAt(Long firstSentAt) {
        this.firstSentAt = firstSentAt;
    }

    public Long getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Long lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Long getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Long roundNo) {
        this.roundNo = roundNo;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
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
