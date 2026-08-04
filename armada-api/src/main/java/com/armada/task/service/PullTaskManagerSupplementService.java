package com.armada.task.service;

import com.armada.task.model.dto.PullTaskManagerSupplementDTO;
import com.armada.task.model.vo.PullTaskManagerSupplementOptionsVO;

/** OP-01 补充管理员选择与不可变指令服务。 */
public interface PullTaskManagerSupplementService {

    /** @return 当前执行行的管理员缺口、可用执行账号和候选账号 */
    PullTaskManagerSupplementOptionsVO options(
            long taskId, long executionId, Long accountGroupId);

    /** 校验并保存一个补充管理员指令，然后唤醒共享调度器。 */
    void supplement(long taskId, long executionId, PullTaskManagerSupplementDTO request);
}
