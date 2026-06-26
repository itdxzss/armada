package com.armada.marketing.mapper;

import com.armada.marketing.model.dto.MarketingTemplateQuery;
import com.armada.marketing.model.entity.MarketingTemplate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 营销模板数据访问。tenant_id 由租户行隔离拦截器自动注入,SQL 不手写 tenant_id 过滤。
 */
@Mapper
public interface MarketingTemplateMapper {

    /** 列表分页查询(SQL 下推 LIMIT/OFFSET)。 */
    List<MarketingTemplate> selectPage(@Param("q") MarketingTemplateQuery query);

    /** 列表总数(与 selectPage 共用筛选片段,口径一致)。 */
    long countPage(@Param("q") MarketingTemplateQuery query);

    /** 按 ID 查未删模板。 */
    MarketingTemplate selectById(@Param("id") Long id);

    /** 名称是否已存在(可排除指定 ID,用于修改场景)。 */
    boolean existsByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /** 插入(不含 tenant_id 列,由拦截器注入)。 */
    int insert(MarketingTemplate entity);

    /** 按 ID 更新。 */
    int updateById(MarketingTemplate entity);

    /** 批量软删除。 */
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);
}
