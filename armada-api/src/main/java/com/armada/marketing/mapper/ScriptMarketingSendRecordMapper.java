package com.armada.marketing.mapper;

import com.armada.marketing.model.entity.ScriptMarketingSendRecord;
import com.armada.shared.paging.PageQuery;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 一群一项一记录，command_id 唯一，不生成重试记录。 */
@Mapper
public interface ScriptMarketingSendRecordMapper {
    /** 插入发送意图，必须和 outbox 在同一事务。 */ int insert(ScriptMarketingSendRecord row);
    /** 按群与项查询原始事实。 */ ScriptMarketingSendRecord findStep(@Param("groupId") Long groupId, @Param("stepIndex") int stepIndex);
    /** 回调只通过当前租户原命令归属。 */ ScriptMarketingSendRecord findCommand(@Param("commandId") String commandId);
    /** 更新等待/暂停状态，结果处理持有任务行锁。 */ int update(ScriptMarketingSendRecord row);
    /** 任务原始发送明细 SQL 分页。 */ List<ScriptMarketingSendRecord> page(@Param("taskId") Long taskId, @Param("q") PageQuery query);
    /** 明细总数。 */ long count(@Param("taskId") Long taskId);
}
