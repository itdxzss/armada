package com.armada.marketing.asset.model.entity;

/** 当前租户的单层图片素材分组。 */
public class ResourceAssetGroup {
    /** 分组所属业务码。 */
    private Integer scope;
    /** @return 分组所属业务 */
    public Integer getScope() { return scope; }
    /** @param scope 分组所属业务 */
    public void setScope(Integer scope) { this.scope = scope; }

    /** 分组主键。 */
    private Long id;
    /** 租户内唯一名称。 */
    private String groupName;
    /** 创建时间，epoch 毫秒。 */
    private Long createdAt;

    /** @return 分组主键 */
    public Long getId() {
        return id;
    }
    /** @param id 分组主键 */
    public void setId(Long id) {
        this.id = id;
    }
    /** @return 分组名称 */
    public String getGroupName() {
        return groupName;
    }
    /** @param groupName 分组名称 */
    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
    /** @return 创建时间 */
    public Long getCreatedAt() {
        return createdAt;
    }
    /** @param createdAt 创建时间 */
    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
