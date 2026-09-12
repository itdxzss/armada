package com.armada.task.model.vo;

import java.util.List;

/**
 * 补充拉手选择页所需的冻结计划、当前事实和候选账号。
 *
 * @param currentPullerCount 当前可用且已确认在群的拉手数
 * @param requiredPullerCount 冻结的计划拉手数
 * @param missingPullerCount 当前允许补充的数量，已扣除有效待进群与结果待确认的名额
 * @param pullerGroupId 当前选择的拉手分组
 * @param currentPullers 当前执行行的角色历史，含已替换记录
 * @param candidates 可选择的在线正常账号
 */
public record PullTaskPullerSupplementOptionsVO(
        int currentPullerCount,
        int requiredPullerCount,
        int missingPullerCount,
        Long pullerGroupId,
        List<PullTaskPullerOptionRoleVO> currentPullers,
        List<PullTaskPullerCandidateVO> candidates) {
}
