package com.armada.task.model.vo;

import java.util.List;

/** 补充管理员选择页所需的实时计数、执行账号与候选账号。 */
public record PullTaskManagerSupplementOptionsVO(
        int currentManagerCount,
        int requiredManagerCount,
        int missingManagerCount,
        Long managerGroupId,
        boolean managerInviteAvailable,
        List<PullTaskManagerOptionRoleVO> currentManagers,
        List<PullTaskManagerOptionRoleVO> executorAccounts,
        List<PullTaskManagerCandidateVO> candidates) {
}
