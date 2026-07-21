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

    /** 按 ID 查询当前租户内未软删除的渠道，供编辑和删除做存在性校验。 */
    PromotionChannel selectActiveChannelById(@Param("id") Long id);

    /** 判断当前平台和追踪 ID 是否存在可安全复用的完整 Token 密文。 */
    int countReusableTrackingToken(
            @Param("channelId") Long channelId,
            @Param("providerType") Integer providerType,
            @Param("trackingId") String trackingId);

    /** 更新渠道可编辑字段，渠道码、创建人和创建时间保持不变。 */
    int updateChannel(PromotionChannel row);

    /** 更新或复活渠道追踪配置；row 中 Token 密文为空时 SQL 保留原密文。 */
    int updateTrackingConfig(PromotionChannelTrackingConfig row);

    /** 平台变化或追踪 ID 清空时清除旧平台凭据，避免敏感 Token 跨平台复用。 */
    int clearTrackingCredentials(
            @Param("channelId") Long channelId,
            @Param("updatedBy") Long updatedBy,
            @Param("updatedAt") long updatedAt);

    /** 软删除渠道追踪配置；没有配置时返回 0，不影响删除主流程。 */
    int softDeleteTrackingConfig(
            @Param("channelId") Long channelId,
            @Param("updatedBy") Long updatedBy,
            @Param("deletedAt") long deletedAt);

    /** 软删除渠道主记录，只更新当前租户内仍有效的行。 */
    int softDeleteChannel(
            @Param("id") Long id,
            @Param("updatedBy") Long updatedBy,
            @Param("deletedAt") long deletedAt);

    /** 使用与列表相同的筛选条件统计总数。 */
    long countPage(PromotionChannelQuery query);

    /** 在 MySQL 中完成筛选、稳定排序和分页，不读取 Token 密文。 */
    List<PromotionChannelVoRow> selectPage(PromotionChannelQuery query);
}
