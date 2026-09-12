package com.armada.marketing.asset.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.marketing.asset.model.enums.ResourceAssetScope;
import java.util.ArrayList;
import java.util.List;

/** 图片素材分页查询；GET 参数通过 setter 绑定。 */
public class ResourceAssetQuery extends PageQuery {

    /** 当前入口业务范围；历史图片在所有业务入口可见。 */
    private ResourceAssetScope scope = ResourceAssetScope.HYPERLINK;
    /** @return 当前入口业务范围 */
    public ResourceAssetScope getScope() { return scope; }
    /** @param scope 当前入口业务范围 */
    public void setScope(ResourceAssetScope scope) { this.scope = scope; }

    /** 分组筛选：null 全部，0 未分组，正数指定分组。 */
    private Long groupId;

    /** @return 分组筛选值 */
    public Long getGroupId() {
        return groupId;
    }
    /** @param groupId 分组筛选值 */
    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    /** 素材业务名称模糊筛选。 */
    private String assetName;
    /** 大小写敏感标签筛选，任意一个标签匹配即命中。 */
    private List<String> tags = new ArrayList<>();
    /** 是否只返回符合模板绑定规则的 JPEG/PNG 素材。 */
    private Boolean selectableOnly;

    /** 创建默认每页 24 条的素材查询。 */
    public ResourceAssetQuery() {
        setPageSize(24);
    }

    /** @return 素材业务名称模糊筛选值 */
    public String getAssetName() {
        return assetName;
    }

    /** @param assetName 素材业务名称模糊筛选值 */
    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    /** @return 大小写敏感标签筛选集合 */
    public List<String> getTags() {
        return tags;
    }

    /** @param tags 大小写敏感标签筛选集合 */
    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    /** @return 是否只返回符合模板绑定规则的素材 */
    public Boolean getSelectableOnly() {
        return selectableOnly;
    }

    /** @param selectableOnly 是否只返回符合模板绑定规则的素材 */
    public void setSelectableOnly(Boolean selectableOnly) {
        this.selectableOnly = selectableOnly;
    }
}
