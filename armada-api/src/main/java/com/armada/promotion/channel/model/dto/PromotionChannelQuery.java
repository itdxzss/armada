package com.armada.promotion.channel.model.dto;

import com.armada.shared.paging.PageQuery;
import java.util.List;

/** 渠道管理分页查询。创建人就是渠道归属用户；上级用户由前端展开为 ownerUserIds。 */
public class PromotionChannelQuery extends PageQuery {

    /** 目标国家精确筛选；与 mixedTargetCountry 同时传入时，混合国家条件优先。 */
    private Long targetCountryId;

    /** 是否只查询“混合（不限国家）”渠道。 */
    private Boolean mixedTargetCountry;

    /** 绑定模板精确筛选。 */
    private Long landingTemplateId;

    /** 创建人精确筛选；当前业务实际匹配 owner_user_id。 */
    private Long creatorUserId;

    /** 上级用户筛选展开后的归属用户 ID 集合，用于 owner_user_id IN 查询。 */
    private List<Long> ownerUserIds;

    /** 页面最新设计默认每页展示 100 条。 */
    public PromotionChannelQuery() {
        setPageSize(100);
    }

    public Long getTargetCountryId() {
        return targetCountryId;
    }

    public void setTargetCountryId(Long targetCountryId) {
        this.targetCountryId = targetCountryId;
    }

    public Boolean getMixedTargetCountry() {
        return mixedTargetCountry;
    }

    public void setMixedTargetCountry(Boolean mixedTargetCountry) {
        this.mixedTargetCountry = mixedTargetCountry;
    }

    public Long getLandingTemplateId() {
        return landingTemplateId;
    }

    public void setLandingTemplateId(Long landingTemplateId) {
        this.landingTemplateId = landingTemplateId;
    }

    public Long getCreatorUserId() {
        return creatorUserId;
    }

    public void setCreatorUserId(Long creatorUserId) {
        this.creatorUserId = creatorUserId;
    }

    public List<Long> getOwnerUserIds() {
        return ownerUserIds;
    }

    public void setOwnerUserIds(List<Long> ownerUserIds) {
        this.ownerUserIds = ownerUserIds;
    }
}
