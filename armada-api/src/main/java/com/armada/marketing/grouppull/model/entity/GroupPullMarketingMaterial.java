package com.armada.marketing.grouppull.model.entity;

/** 拉群营销任务料子池记录，映射 group_pull_marketing_material。 */
public class GroupPullMarketingMaterial {

    /** 主键 ID。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 统一营销任务 ID。 */
    private Long taskId;

    /** 有效号码在上传文件中的稳定顺序。 */
    private Integer lineNo;

    /** 清洗后的手机号。 */
    private String phone;

    /** 当前料子状态码。 */
    private Integer status;

    /** 当前预留或最终使用该料子的执行 ID。 */
    private Long currentExecutionId;

    /** 创建时间，epoch 毫秒。 */
    private Long createdAt;

    /** 更新时间，epoch 毫秒。 */
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

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getCurrentExecutionId() {
        return currentExecutionId;
    }

    public void setCurrentExecutionId(Long currentExecutionId) {
        this.currentExecutionId = currentExecutionId;
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
