package com.armada.promotion.channel.mapper;

import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.entity.PromotionChannel;
import com.armada.promotion.channel.model.entity.PromotionChannelTrackingConfig;
import com.armada.promotion.channel.model.entity.PromotionDomain;
import com.armada.promotion.channel.model.entity.PromotionLandingTemplate;
import com.armada.promotion.channel.model.vo.PromotionChannelDetailRow;
import com.armada.promotion.channel.model.vo.PromotionChannelProbeConfigRow;
import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.channel.model.vo.PromotionChannelRuntimeRow;
import com.armada.promotion.channel.model.vo.PromotionChannelVoRow;
import com.armada.promotion.channel.model.vo.PromotionChannelOptionVO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
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

    /** 按模板查询当前租户内有效域名绑定，保证一个模板只能对应一个域名。 */
    PromotionDomain selectActiveDomainByTemplateId(@Param("templateId") Long templateId);

    /** 唯一键冲突后按域名当前读，绕过 REPEATABLE READ 的旧快照。 */
    PromotionDomain selectActiveDomainByHostForUpdate(@Param("domainHost") String domainHost);

    /** 唯一键冲突后按模板当前读，识别并发建立的模板域名映射。 */
    PromotionDomain selectActiveDomainByTemplateIdForUpdate(@Param("templateId") Long templateId);

    /** 锁定准备被渠道引用的有效域名，防止最后渠道删除并发释放该绑定。 */
    PromotionDomain selectActiveDomainByIdForUpdate(@Param("id") Long id);

    /** 按渠道锁定其有效域名绑定，但不提前锁渠道行，避免同域名并发删除形成锁环。 */
    PromotionDomain selectActiveDomainByChannelIdForUpdate(@Param("channelId") Long channelId);

    /** 新增域名与模板绑定，主键回填到实体。 */
    int insertDomain(PromotionDomain row);

    /** 新增渠道主记录，主键回填到实体。 */
    int insertChannel(PromotionChannel row);

    /** 新增 Pixel/CAPI 追踪配置；Token 字段只能传入应用层密文。 */
    int insertTrackingConfig(PromotionChannelTrackingConfig row);

    /** 按 ID 查询当前租户内未软删除的渠道，供编辑和删除做存在性校验。 */
    PromotionChannel selectActiveChannelById(@Param("id") Long id);

    /** 查询当前租户内未删除渠道的编辑回显字段，不返回 Token 材料。 */
    PromotionChannelDetailRow selectDetailById(@Param("id") Long id);

    /**
     * 公开接口按渠道码和域名解析所属租户并读取最小运行时配置。
     *
     * <p>公开请求没有租户上下文，因此只对该只读 SQL 关闭自动租户注入；SQL 内必须显式约束
     * 租户启用，渠道、域名和模板 tenant_id 一致，并且不得投影任何敏感字段。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    PromotionChannelRuntimeRow selectRuntimeByCodeAndHost(
            @Param("channelCode") String channelCode,
            @Param("domainHost") String domainHost);

    /**
     * 公开配对入口按渠道码和受信任转发域名解析租户、渠道与代理地区。
     *
     * <p>公开请求没有租户上下文，因此关闭自动租户注入；SQL 内显式校验租户、渠道、域名和模板均有效。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    PromotionChannelPairingContextRow selectPairingContextByCodeAndHost(
            @Param("channelCode") String channelCode,
            @Param("domainHost") String domainHost);

    /** CAPI 内部投递专用敏感配置查询；结果只能在 Service 内使用，禁止返回 Controller。 */
    PromotionChannelProbeConfigRow selectProbeConfigByChannelId(@Param("id") Long id);

    /** 原子抢占探测状态；处于有效探测窗口内时返回 0，防止重复调用平台。 */
    int markProbeRunning(
            @Param("row") PromotionChannelTrackingConfig row,
            @Param("staleBefore") Long staleBefore,
            @Param("cooldownBefore") Long cooldownBefore);

    /** 回写成功或脱敏失败结果；开始时间不匹配时拒绝旧请求覆盖新一轮探测。 */
    int updateProbeResult(
            @Param("row") PromotionChannelTrackingConfig row,
            @Param("startedAt") Long startedAt);

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

    /**
     * 当前读检查同租户域名是否仍被其他有效渠道引用。
     *
     * <p>MyBatis-Plus 会把 {@code LIMIT ... FOR UPDATE} 重排为 MySQL 非法语序，因此该方法关闭
     * 租户 SQL 改写，并要求调用方显式传入当前租户 ID 保持隔离。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    Long selectAnyActiveChannelIdByDomainForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("domainId") Long domainId);

    /** 最后一个有效渠道删除后释放域名与模板绑定，同时保留历史记录。 */
    int softDeleteDomain(
            @Param("id") Long id,
            @Param("updatedBy") Long updatedBy,
            @Param("deletedAt") long deletedAt);

    /** 使用与列表相同的筛选条件统计总数。 */
    long countPage(PromotionChannelQuery query);

    /** 在 MySQL 中完成筛选、稳定排序和分页，不读取 Token 密文。 */
    List<PromotionChannelVoRow> selectPage(PromotionChannelQuery query);

    /** 当前租户启用渠道下拉，按名称和 ID 稳定排序。 */
    List<PromotionChannelOptionVO> selectOptions();
}
