package com.armada.hyperlink.template.mapper;

import com.armada.hyperlink.template.model.dto.HyperlinkTemplateQuery;
import com.armada.hyperlink.template.model.entity.HyperlinkTemplate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 超链模板数据访问，所有 SQL 由 MyBatis-Plus 租户插件自动限定当前租户。 */
@Mapper
public interface HyperlinkTemplateMapper {

    /** 按筛选条件统计有效模板。 */
    long countPage(@Param("q") HyperlinkTemplateQuery query);

    /** 按筛选条件分页查询有效模板。 */
    List<HyperlinkTemplate> selectPage(@Param("q") HyperlinkTemplateQuery query);

    /** 按 ID 查询当前租户有效模板。 */
    HyperlinkTemplate selectById(@Param("id") Long id);

    /** 查询轻量候选，按最近更新时间倒序。 */
    List<HyperlinkTemplate> selectOptions(
            @Param("messageType") Integer messageType,
            @Param("keyword") String keyword,
            @Param("limit") int limit);

    /** 查询有效同名模板是否存在，可排除正在编辑的模板。 */
    boolean existsByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /** 新增模板并回填主键。 */
    int insert(HyperlinkTemplate entity);

    /** 按 ID 与版本完整更新业务字段，并令版本加一。 */
    int updateByIdAndVersion(
            @Param("entity") HyperlinkTemplate entity,
            @Param("expectedVersion") int expectedVersion);

    /** 按 ID 软删除当前租户有效模板。 */
    int softDelete(@Param("id") Long id, @Param("deletedAt") long deletedAt);
}
