package com.armada.group.model.entity;

/** 群链接健康状态实体,映射 group_link_health。 */
public class GroupLinkHealth {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 关联 group_link.id。 */
    private Long groupLinkId;

    /** 健康状态:1=可用 2=链接失效 3=不可用;NULL=未检测。 */
    private Integer healthStatus;

    /** 是否被 WhatsApp 封禁:NULL=未知 0=未封禁 1=已封禁,映射库列 is_banned。 */
    private Boolean banned;

    /** 当前群成员数量。 */
    private Integer currentCount;

    /** 最近一次健康检测时间(epoch毫秒)。 */
    private Long lastCheckAt;

    /** 最近一次健康检测失败原因。 */
    private String lastHealthError;

    /** 连续健康检测失败次数;检测成功后归零。 */
    private Integer healthFailureCount;

    /** 创建时间(epoch毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch毫秒)。 */
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

    public Integer getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(Integer healthStatus) {
        this.healthStatus = healthStatus;
    }

    public Boolean getBanned() {
        return banned;
    }

    public void setBanned(Boolean banned) {
        this.banned = banned;
    }

    public Integer getCurrentCount() {
        return currentCount;
    }

    public void setCurrentCount(Integer currentCount) {
        this.currentCount = currentCount;
    }

    public Long getLastCheckAt() {
        return lastCheckAt;
    }

    public void setLastCheckAt(Long lastCheckAt) {
        this.lastCheckAt = lastCheckAt;
    }

    public String getLastHealthError() {
        return lastHealthError;
    }

    public void setLastHealthError(String lastHealthError) {
        this.lastHealthError = lastHealthError;
    }

    public Integer getHealthFailureCount() {
        return healthFailureCount;
    }

    public void setHealthFailureCount(Integer healthFailureCount) {
        this.healthFailureCount = healthFailureCount;
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
