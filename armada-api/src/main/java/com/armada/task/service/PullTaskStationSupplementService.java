package com.armada.task.service;

import com.armada.task.model.dto.PullTaskStationSupplementDTO;
import com.armada.task.model.vo.PullTaskStationSupplementOptionsVO;

/** 普通群链接单群补充站台用例。 */
public interface PullTaskStationSupplementService {

    /** 查询当前缺口与所选账号分组的额外候选。 */
    PullTaskStationSupplementOptionsVO options(
            long taskId, long executionId, Long accountGroupId);

    /** 锁定补充站台并恢复拉人检查点，不直接改变群成员。 */
    void supplement(long taskId, long executionId, PullTaskStationSupplementDTO request);
}
