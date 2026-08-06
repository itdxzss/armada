package com.armada.task.scheduler;

/**
 * 管理员设置阶段从实时成员列表提取的权限事实。
 *
 * @param promoterStillAdmin 提权候选当前仍是群主或管理员
 * @param managerAlreadyAdmin 任务管理员当前已是群主或管理员
 */
public record PullTaskManagerAdminObservation(
        boolean promoterStillAdmin,
        boolean managerAlreadyAdmin) {
}
