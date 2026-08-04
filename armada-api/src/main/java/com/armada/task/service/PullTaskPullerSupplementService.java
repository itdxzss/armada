package com.armada.task.service;

import com.armada.task.model.dto.PullTaskPullerSupplementDTO;
import com.armada.task.model.vo.PullTaskPullerSupplementOptionsVO;

/** 普通群链接单群的拉手补充选择与不可变指令服务。 */
public interface PullTaskPullerSupplementService {

    PullTaskPullerSupplementOptionsVO options(
            long taskId, long executionId, Long accountGroupId);

    void supplement(long taskId, long executionId, PullTaskPullerSupplementDTO request);
}
