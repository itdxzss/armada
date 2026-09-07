package com.armada.marketing.mapper;

import com.armada.marketing.model.dto.ScriptMarketingQuery;
import com.armada.marketing.model.entity.ScriptMarketingTask;
import com.armada.marketing.model.vo.ScriptMarketingTaskVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 新任务配置与当前用户分页；所有操作由租户插件隔离。 */
@Mapper
public interface ScriptMarketingTaskMapper {
    /** 保存草稿。 */ int insert(ScriptMarketingTask row);
    /** 锁定任务，串行化操作、执行和回调。 */ ScriptMarketingTask lock(@Param("id") Long id);
    /** 读取配置。 */ ScriptMarketingTask find(@Param("id") Long id);
    /** 保存未启动配置。 */ int updateDraft(ScriptMarketingTask row);
    /** 更新任务状态。 */ int updateState(ScriptMarketingTask row);
    /** 当前用户任务数量。 */ long count(@Param("q") ScriptMarketingQuery query, @Param("owner") Long owner);
    /** SQL 分页并聚合单项结果。 */ List<ScriptMarketingTaskVO> page(@Param("q") ScriptMarketingQuery query, @Param("owner") Long owner);
    /** 查询任务统计。 */ ScriptMarketingTaskVO summary(@Param("id") Long id);
}
