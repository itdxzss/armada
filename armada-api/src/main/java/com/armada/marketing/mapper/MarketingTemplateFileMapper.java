package com.armada.marketing.mapper;

import com.armada.marketing.asset.model.dto.ResourceAssetQuery;
import com.armada.marketing.asset.model.vo.ResourceAssetReferenceCountVO;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 营销模板图片文件数据访问。tenant_id 由租户行隔离拦截器自动注入。
 */
@Mapper
public interface MarketingTemplateFileMapper {

    /**
     * 插入当前租户图片文件并回填 ID。
     *
     * @param file 待持久化图片文件
     * @return 插入行数
     */
    int insert(MarketingTemplateFile file);

    /**
     * 按 ID 查询当前租户未删除图片文件，包含原始字节。
     *
     * @param id 图片文件 ID
     * @return 匹配文件；不存在或跨租户时为空
     */
    MarketingTemplateFile selectById(@Param("id") Long id);

    /**
     * 分页查询当前租户素材元数据，结果不含 MEDIUMBLOB content。
     *
     * @param query 名称、标签、可选绑定条件和分页参数
     * @return 当前页素材元数据
     */
    List<MarketingTemplateFile> selectAssetPage(@Param("q") ResourceAssetQuery query);

    /**
     * 统计当前租户素材总数，筛选口径与分页查询一致。
     *
     * @param query 名称、标签和可选绑定条件
     * @return 匹配素材数
     */
    long countAssetPage(@Param("q") ResourceAssetQuery query);

    /**
     * 按 ID 查询当前租户未删除素材元数据，结果不含 MEDIUMBLOB content。
     *
     * @param id 素材文件 ID
     * @return 匹配素材；不存在或跨租户时为空
     */
    MarketingTemplateFile selectAssetMetadataById(@Param("id") Long id);

    /**
     * 按 ID 锁定当前租户素材并读取完整内容。
     *
     * <p>租户条件由全局租户拦截器注入；本查询不得绕过该拦截器。</p>
     *
     * @param id 素材文件 ID
     * @return 已锁定素材；不存在、跨租户或已删除时为空
     */
    MarketingTemplateFile selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按 ID 轻量锁定当前租户素材，不读取图片字节。
     *
     * <p>素材编辑和删除只需串行化对同一主键的写入，租户条件由全局租户拦截器注入。</p>
     *
     * @param id 素材文件 ID
     * @return 已锁定的素材 ID；不存在、跨租户或已删除时为空
     */
    Long selectIdByIdForUpdate(@Param("id") Long id);

    /**
     * 更新当前租户素材名称和审计时间。
     *
     * @param id 素材文件 ID
     * @param assetName 已归一化素材名称
     * @param updatedAt 更新时间，epoch 毫秒
     * @return 更新行数
     */
    int updateAssetMetadata(
            @Param("id") Long id,
            @Param("assetName") String assetName,
            @Param("updatedAt") long updatedAt);

    /**
     * 软删除当前租户尚未删除的素材。
     *
     * @param id 素材文件 ID
     * @param deletedAt 删除时间，epoch 毫秒
     * @return 更新行数
     */
    int softDeleteAsset(@Param("id") Long id, @Param("deletedAt") long deletedAt);

    /**
     * 批量统计素材在现有模板和超链任务中的去重引用次数。
     *
     * @param tenantId 可信租户上下文中的租户 ID
     * @param ids 当前页素材 ID
     * @return 有引用素材的聚合结果
     */
    @InterceptorIgnore(tenantLine = "true")
    List<ResourceAssetReferenceCountVO> selectReferenceCounts(
            @Param("tenantId") Long tenantId,
            @Param("ids") List<Long> ids);

    /**
     * 精确统计一个素材在现有模板和超链任务中的去重引用次数。
     *
     * @param tenantId 可信租户上下文中的租户 ID
     * @param id 素材文件 ID
     * @return 有效模板与任务的去重引用数
     */
    @InterceptorIgnore(tenantLine = "true")
    long countReferences(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
