package com.armada.task.model.entity;

/** TXT 料子号码及其入群、提权结果，映射 {@code pull_task_material_member}。 */
public class PullTaskMaterialMember {

    /** 料子成员主键。 */
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 所属执行行 ID(→pull_task_group_execution.id)。 */
    private Long groupExecutionId;

    /** 文件内去重后稳定顺序。 */
    private Integer memberSeq;

    /** 首次有效出现的原始行号。 */
    private Integer sourceLineNo;

    /** 归一化号码(7-15 位含国家码纯数字)。 */
    private String normalizedPhone;

    /** 是否带 A/a 需设群管理员标识：0 否 1 是。 */
    private Integer adminRequired;

    /** 消费本料子的拉人调用 ID；null 表示尚未消费。 */
    private Long pullCallId;

    /** 入群结果，取值见 PullTaskMaterialPullStatus。 */
    private Integer pullStatus;

    /** 入群失败原因码。 */
    private String pullReasonCode;

    /** 入群失败原因描述(已脱敏)。 */
    private String pullReasonMessage;

    /** 成功入群后的成员 JID。 */
    private String waJid;

    /** 入群结果回写时间(epoch 毫秒)。 */
    private Long pullResultAt;

    /** 提权结果，取值见 PullTaskMaterialAdminStatus。 */
    private Integer adminStatus;

    /** 提权协议命令 ID。 */
    private String adminCommandId;

    /** 提权失败原因码。 */
    private String adminReasonCode;

    /** 提权结果回写时间(epoch 毫秒)。 */
    private Long adminResultAt;

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

    public Long getGroupExecutionId() {
        return groupExecutionId;
    }

    public void setGroupExecutionId(Long groupExecutionId) {
        this.groupExecutionId = groupExecutionId;
    }

    public Integer getMemberSeq() {
        return memberSeq;
    }

    public void setMemberSeq(Integer memberSeq) {
        this.memberSeq = memberSeq;
    }

    public Integer getSourceLineNo() {
        return sourceLineNo;
    }

    public void setSourceLineNo(Integer sourceLineNo) {
        this.sourceLineNo = sourceLineNo;
    }

    public String getNormalizedPhone() {
        return normalizedPhone;
    }

    public void setNormalizedPhone(String normalizedPhone) {
        this.normalizedPhone = normalizedPhone;
    }

    public Integer getAdminRequired() {
        return adminRequired;
    }

    public void setAdminRequired(Integer adminRequired) {
        this.adminRequired = adminRequired;
    }

    public Long getPullCallId() {
        return pullCallId;
    }

    public void setPullCallId(Long pullCallId) {
        this.pullCallId = pullCallId;
    }

    public Integer getPullStatus() {
        return pullStatus;
    }

    public void setPullStatus(Integer pullStatus) {
        this.pullStatus = pullStatus;
    }

    public String getPullReasonCode() {
        return pullReasonCode;
    }

    public void setPullReasonCode(String pullReasonCode) {
        this.pullReasonCode = pullReasonCode;
    }

    public String getPullReasonMessage() {
        return pullReasonMessage;
    }

    public void setPullReasonMessage(String pullReasonMessage) {
        this.pullReasonMessage = pullReasonMessage;
    }

    public String getWaJid() {
        return waJid;
    }

    public void setWaJid(String waJid) {
        this.waJid = waJid;
    }

    public Long getPullResultAt() {
        return pullResultAt;
    }

    public void setPullResultAt(Long pullResultAt) {
        this.pullResultAt = pullResultAt;
    }

    public Integer getAdminStatus() {
        return adminStatus;
    }

    public void setAdminStatus(Integer adminStatus) {
        this.adminStatus = adminStatus;
    }

    public String getAdminCommandId() {
        return adminCommandId;
    }

    public void setAdminCommandId(String adminCommandId) {
        this.adminCommandId = adminCommandId;
    }

    public String getAdminReasonCode() {
        return adminReasonCode;
    }

    public void setAdminReasonCode(String adminReasonCode) {
        this.adminReasonCode = adminReasonCode;
    }

    public Long getAdminResultAt() {
        return adminResultAt;
    }

    public void setAdminResultAt(Long adminResultAt) {
        this.adminResultAt = adminResultAt;
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
