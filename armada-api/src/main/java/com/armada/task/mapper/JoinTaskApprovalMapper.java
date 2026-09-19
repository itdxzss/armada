package com.armada.task.mapper;

import com.armada.task.model.dto.JoinTaskDispatchCandidate;
import com.armada.task.model.entity.JoinTaskApproval;
import com.armada.task.model.entity.JoinTaskResult;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 待审核分支的持久化，具体操作始终恢复明细所属租户。 */
@Mapper
public interface JoinTaskApprovalMapper {
    /** 只扫描到期记录的路由 ID，包含已停止任务以收敛未完成记录。 */
    @InterceptorIgnore(tenantLine = "true")
    List<JoinTaskDispatchCandidate> scan(@Param("now") long now);
    /** 幂等接管当前 SUBMITTED 尝试；旧回执不能触发。 */
    int begin(@Param("row") JoinTaskResult row, @Param("now") long now);
    /** 与接管明细同事务创建唯一子记录。 */
    int insert(JoinTaskApproval row);
    /** 锁定单条恢复记录。 */
    JoinTaskApproval lock(@Param("resultId") Long resultId);
    /** 调用者持锁并核对领取版本。 */
    int update(JoinTaskApproval row);
    /** 将已确认终结的恢复记录交回现有进群结果状态机，同事务执行。 */
    int release(@Param("row") JoinTaskResult row);
}
