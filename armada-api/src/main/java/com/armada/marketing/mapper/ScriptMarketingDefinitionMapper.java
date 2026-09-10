package com.armada.marketing.mapper;
import com.armada.marketing.model.entity.ScriptMarketingDefinition;
import com.armada.marketing.model.dto.ScriptMarketingQuery;
import com.armada.marketing.model.vo.ScriptDefinitionSummaryVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 可复用剧本持久化，查询使用当前租户和创建人双重边界。 */
@Mapper
public interface ScriptMarketingDefinitionMapper {
    /** 创建定义，不创建任何任务。 */ int insert(ScriptMarketingDefinition row);
    /** 查找当前租户未删除定义。 */ ScriptMarketingDefinition find(@Param("id") Long id);
    /** 编辑与删除共用行锁。 */ ScriptMarketingDefinition lock(@Param("id") Long id);
    /** 保存完整定义。 */ int update(ScriptMarketingDefinition row);
    /** 删除仅移出剧本库，已保存任务的快照保持独立。 */ int delete(@Param("id") Long id, @Param("now") long now);
    /** SQL 分页摘要。 */ List<ScriptDefinitionSummaryVO> page(@Param("q") ScriptMarketingQuery query, @Param("owner") Long owner);
    /** 与列表同条件计数。 */ long count(@Param("q") ScriptMarketingQuery query, @Param("owner") Long owner);
}
