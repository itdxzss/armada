package com.armada.task.model.vo;

import java.util.List;

/** 补充拉手选择页所需的冻结计划、当前事实和候选账号。 */
public record PullTaskPullerSupplementOptionsVO(
        int currentPullerCount,
        int requiredPullerCount,
        int missingPullerCount,
        Long pullerGroupId,
        boolean managerInviteAvailable,
        List<PullTaskPullerOptionRoleVO> currentPullers,
        List<PullTaskPullerCandidateVO> candidates) {
}
