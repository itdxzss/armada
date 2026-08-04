package com.armada.task.service;

import com.armada.shared.response.PageResult;
import com.armada.task.model.dto.PullTaskStandardExecutionQuery;
import com.armada.task.model.vo.PullTaskStandardExecutionDetailVO;
import com.armada.task.model.vo.PullTaskStandardMemberVO;
import com.armada.task.model.vo.PullTaskStandardTaskDetailVO;
import java.util.List;

/** 普通群链接任务 M1 最小详情读服务。 */
public interface PullTaskStandardReadService {

    /** @return 任务事实和聚合；执行行由分页接口读取 */
    PullTaskStandardTaskDetailVO task(long taskId);

    /** @return 服务端筛选和分页后的单群执行工作台 */
    PageResult<com.armada.task.model.vo.PullTaskStandardExecutionSummaryVO> executions(
            long taskId, PullTaskStandardExecutionQuery query);

    /** @return 单执行行、角色账号和逐调用事实 */
    PullTaskStandardExecutionDetailVO execution(long taskId, long executionId);

    /** @return 单执行行的逐料子结果 */
    List<PullTaskStandardMemberVO> members(long taskId, long executionId);
}
