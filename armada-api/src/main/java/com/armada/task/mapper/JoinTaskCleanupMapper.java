package com.armada.task.mapper;

import com.armada.task.model.dto.JoinTaskDispatchCandidate;
import com.armada.task.model.entity.JoinTaskCleanup;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 进群明细的清理子记录；所有具体读写都在当前租户内。 */
@Mapper
public interface JoinTaskCleanupMapper {
    /** 跨租户只扫描路由 ID，实际执行恢复租户上下文。 */
    @InterceptorIgnore(tenantLine = "true")
    List<JoinTaskDispatchCandidate> scan(@Param("now") long now);
    /** 提权成功事务中创建唯一后续阶段。 */
    int insert(JoinTaskCleanup row);
    /** 锁定明细的清理记录。 */
    JoinTaskCleanup lock(@Param("resultId") Long resultId);
    /** 保存阶段与操作游标，调用者持有行锁。 */
    int update(JoinTaskCleanup row);
}
