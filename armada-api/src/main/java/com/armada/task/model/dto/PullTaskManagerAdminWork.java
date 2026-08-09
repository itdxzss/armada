package com.armada.task.model.dto;

import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;

/**
 * 管理员设置阶段事务外实时核验所需的冻结工作项。
 *
 * @param tenantId 租户 ID
 * @param taskId 拉群任务 ID
 * @param executionId 群执行行 ID
 * @param expectedVersion 执行行期望版本
 * @param lockOwner 调度租约持有者
 * @param groupJid 目标群 JID
 * @param manager 待提权任务管理员角色
 * @param promoter 候选既有管理员协议账号
 * @param promoterRole 候选在执行行内的提权角色
 * @param action 提权动作
 */
public record PullTaskManagerAdminWork(
        long tenantId,
        long taskId,
        long executionId,
        int expectedVersion,
        String lockOwner,
        String groupJid,
        PullTaskGroupAccount manager,
        GroupExecutionAccount promoter,
        PullTaskGroupAccount promoterRole,
        PullTaskAccountAction action) {

}
