package com.armada.marketing.grouppull.model.entity;

/** 单次建群执行与料子的历史关系，映射 group_pull_marketing_execution_material。 */
public class GroupPullMarketingExecutionMaterial {

    /** 主键 ID。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 建群执行 ID。 */
    private Long executionId;

    /** 料子 ID。 */
    private Long materialId;

    /** 联表读取的料子手机号，不持久化到关系表。 */
    private String materialPhone;

    /** 料子在本群内的抽取顺序。 */
    private Integer allocationNo;

    /** 建群账号单向添加料子的好友结果码。 */
    private Integer friendStatus;

    /** 添加好友失败原因。 */
    private String friendFailureReason;

    /** 料子实际进群结果码。 */
    private Integer entryStatus;

    /** 料子进群失败原因。 */
    private String entryFailureReason;

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

    public Long getExecutionId() {
        return executionId;
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public String getMaterialPhone() {
        return materialPhone;
    }

    public void setMaterialPhone(String materialPhone) {
        this.materialPhone = materialPhone;
    }

    public Integer getAllocationNo() {
        return allocationNo;
    }

    public void setAllocationNo(Integer allocationNo) {
        this.allocationNo = allocationNo;
    }

    public Integer getFriendStatus() {
        return friendStatus;
    }

    public void setFriendStatus(Integer friendStatus) {
        this.friendStatus = friendStatus;
    }

    public String getFriendFailureReason() {
        return friendFailureReason;
    }

    public void setFriendFailureReason(String friendFailureReason) {
        this.friendFailureReason = friendFailureReason;
    }

    public Integer getEntryStatus() {
        return entryStatus;
    }

    public void setEntryStatus(Integer entryStatus) {
        this.entryStatus = entryStatus;
    }

    public String getEntryFailureReason() {
        return entryFailureReason;
    }

    public void setEntryFailureReason(String entryFailureReason) {
        this.entryFailureReason = entryFailureReason;
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
