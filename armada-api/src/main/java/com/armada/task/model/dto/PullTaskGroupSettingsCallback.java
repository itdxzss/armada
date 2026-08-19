package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskGroupSettingItem;
import com.armada.task.model.enums.PullTaskGroupSettingsProtocolOutcome;

/**
 * 拉群群设置协议结果回写参数。
 *
 * <p>放开加人权限与关闭进群审核仍是一条命令一个设置项，命令级 {@code outcome} 就是该设置项的
 * 结果，设置项由动作行的 {@code action_type} 推出。「群信息设置」整块下发时一条命令要改多项，
 * 失败时靠 {@code failedItem} 指明是哪一项没设上。</p>
 *
 * <p>一条命令多项失败时**只回报第一个失败项**（业务确认 2026-08-19），不带明细列表：动作行只有
 * 一个原因码字段，运营要的也只是「先去看哪一项」。「第一个」按协议层固定的执行顺序判定：
 * 群名 → 头像 → 群描述 → 禁言 → 编辑权限 → 加人权限 → 入群审批 → 限时消息，
 * 即先设看得见的资料再设权限类。该顺序与 {@link PullTaskGroupSettingItem} 的声明顺序一致，
 * 由协议层两侧保证，armada 侧不重排。</p>
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 群设置动作 ID
 * @param accountId 执行设置的任务管理员账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param commandId Outbox 命令 ID
 * @param attemptNo 尝试序号
 * @param outcome 协议结果
 * @param failedItem 「群信息设置」里按上述顺序**第一个**没设上的项；成功或单项设置命令时为
 *        {@code null}
 * @param reasonCode 协议稳定原因码
 * @param reasonMessage 协议原始说明，业务层不会直接落库
 * @param occurredAt 结果发生时间
 */
public record PullTaskGroupSettingsCallback(
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long actionId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        PullTaskGroupSettingsProtocolOutcome outcome,
        PullTaskGroupSettingItem failedItem,
        String reasonCode,
        String reasonMessage,
        long occurredAt
) {
}
