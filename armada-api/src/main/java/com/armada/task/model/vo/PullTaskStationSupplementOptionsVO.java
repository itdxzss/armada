package com.armada.task.model.vo;

import java.util.List;

/** 补充站台页所需的冻结配置、当前缺口与可锁定候选。 */
public record PullTaskStationSupplementOptionsVO(
        int requiredStationCount,
        int missingStationCount,
        Long stationGroupId,
        List<PullTaskStationCandidateVO> candidates) {
}
