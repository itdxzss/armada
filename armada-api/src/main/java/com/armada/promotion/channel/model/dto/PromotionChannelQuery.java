package com.armada.promotion.channel.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;
import java.util.List;

/** 渠道管理分页查询。创建人就是渠道归属用户；上级用户由前端展开为 ownerUserIds。 */
public class PromotionChannelQuery extends PageQuery {

    /** 目标国家精确筛选：真实国家传 ISO2（如 IN），混合国家传 MIXED。 */
    private String targetCountry;

    /** 绑定模板精确筛选。 */
    private Long landingTemplateId;

    /** 创建人精确筛选；当前业务实际匹配 owner_user_id。 */
    private Long creatorUserId;

    /** 上级用户筛选展开后的归属用户 ID 集合，用于 owner_user_id IN 查询。 */
    private List<Long> ownerUserIds;

    /** 服务端从可信认证身份注入的数据范围，不参与 HTTP 参数绑定。 */
    private DataScope dataScope;

    /** 页面最新设计默认每页展示 100 条。 */
    public PromotionChannelQuery() {
        setPageSize(100);
    }

    public String getTargetCountry() {
        return targetCountry;
    }

    public void setTargetCountry(String targetCountry) {
        this.targetCountry = targetCountry;
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

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入服务端范围。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }
}
