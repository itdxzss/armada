package com.armada.task.model.dto;

/**
 * 用户确认的单个补充管理员指令。
 *
 * @param accountGroupId 管理员候选账号分组
 * @param accountId 明确选择的候选账号
 * @param entryMode 进入方式，见 PullTaskAccountEntryMode
 * @param executorRoleRowId 当前管理员邀请时的执行角色行；踩链接时为空
 */
public record PullTaskManagerSupplementDTO(
        Long accountGroupId,
        Long accountId,
        Integer entryMode,
        Long executorRoleRowId) {
}
