package com.armada.promotion.channel.mapper;

import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.entity.PromotionChannel;
import com.armada.promotion.channel.model.entity.PromotionChannelTrackingConfig;
import com.armada.promotion.channel.model.entity.PromotionDomain;
import com.armada.promotion.channel.model.entity.PromotionLandingTemplate;
import com.armada.promotion.channel.model.vo.PromotionChannelVoRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 推广渠道数据访问；tenant_id 由 MyBatis 租户拦截器自动注入。 */
@Mapper
public interface PromotionChannelMapper {

    /** 按 ID 查询当前租户内启用且未删除的模板。 */
    PromotionLandingTemplate selectAvailableTemplateById(@Param("id") Long id);

    /** 按规范化域名查询当前租户内有效绑定。 */
    PromotionDomain selectActiveDomainByHost(@Param("domainHost") String domainHost);

    /** 新增域名与模板绑定，主键回填到实体。 */
    int insertDomain(PromotionDomain row);

    /** 新增渠道主记录，主键回填到实体。 */
    int insertChannel(PromotionChannel row);

    /** 新增 Pixel/CAPI 追踪配置；Token 字段只能传入应用层密文。 */
    int insertTrackingConfig(PromotionChannelTrackingConfig row);

    /** 使用与列表相同的筛选条件统计总数。 */
    long countPage(PromotionChannelQuery query);

    /** 在 MySQL 中完成筛选、稳定排序和分页，不读取 Token 密文。 */
    List<PromotionChannelVoRow> selectPage(PromotionChannelQuery query);
}
