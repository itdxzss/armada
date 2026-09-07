package com.armada.marketing.mapper;

import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 每群独立进度，在任务行锁内修改。 */
@Mapper
public interface ScriptMarketingGroupMapper {
    /** 添加固定目标。 */ int insert(ScriptMarketingGroup row);
    /** 替换草稿目标前删除旧草稿目标。 */ int deleteDraftGroups(@Param("taskId") Long taskId);
    /** 任务目标，最多 100 个。 */ List<ScriptMarketingGroup> list(@Param("taskId") Long taskId);
    /** 读取单群进度。 */ ScriptMarketingGroup find(@Param("id") Long id);
    /** 保存下一项时间、下标与暂停等待。 */ int update(ScriptMarketingGroup row);
    /** 后台仅扫描候选的租户和主键，worker 必须重建租户并持任务锁复核。 */
    @InterceptorIgnore(tenantLine = "true")
    List<ScriptMarketingGroup> due(@Param("now") long now, @Param("limit") int limit);
}
