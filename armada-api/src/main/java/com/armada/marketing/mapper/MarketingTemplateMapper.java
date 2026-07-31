package com.armada.marketing.mapper;

import com.armada.marketing.model.dto.MarketingTemplateQuery;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 营销模板数据访问。普通 SQL 的 tenant_id 由租户行隔离拦截器自动注入；
 * MySQL 锁行语句显式传入 tenantId，避免租户 SQL 改写破坏锁子句顺序。
 */
@Mapper
public interface MarketingTemplateMapper {

    /** 列表分页查询(SQL 下推 LIMIT/OFFSET)。 */
    List<MarketingTemplate> selectPage(@Param("q") MarketingTemplateQuery query);

    /** 列表总数(与 selectPage 共用筛选片段,口径一致)。 */
    long countPage(@Param("q") MarketingTemplateQuery query);

    /** 按 ID 查未删模板。 */
    MarketingTemplate selectById(@Param("id") Long id);

    /** 按 ID 集合批量查询未删除模板；调用方保证 ids 非空且已去重。 */
    List<MarketingTemplate> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 按 ID 查询并锁定未删除模板。
     *
     * <p>创建营销任务时使用，和模板删除事务串行化，避免任务引用到并发软删除的模板。</p>
     */
    MarketingTemplate selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按升序查询并锁定一组未删除模板 ID。
     *
     * <p>调用方必须传入当前租户 ID 及去重、升序模板 ID；固定锁顺序可以降低并发批量删除时的
     * 死锁概率。该查询关闭自动租户改写，SQL 必须显式限定 tenant_id。</p>
     *
     * @param tenantId 当前租户 ID，必须来自租户上下文
     * @param ids 去重并按升序排列的模板 ID
     * @return 当前租户内已锁定的未删除模板 ID
     */
    @InterceptorIgnore(tenantLine = "true")
    List<Long> selectExistingIdsForUpdate(@Param("tenantId") Long tenantId,
                                          @Param("ids") List<Long> ids);

    /** 名称是否已存在(可排除指定 ID,用于修改场景)。 */
    boolean existsByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /** 插入(不含 tenant_id 列,由拦截器注入)。 */
    int insert(MarketingTemplate entity);

    /** 按 ID 更新。 */
    int updateById(MarketingTemplate entity);

    /** 批量软删除。 */
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);
}
