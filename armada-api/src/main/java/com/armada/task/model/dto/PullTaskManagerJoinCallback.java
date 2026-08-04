package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskManagerJoinProtocolOutcome;
import java.util.Objects;

/**
 * 普通拉群管理员踩链接的协议结果。
 *
 * @param tenantId 所属租户 ID
 * @param pullTaskId 普通拉群任务 ID
 * @param groupExecutionId 群链接执行行 ID
 * @param actionId 踩链接动作行 ID
 * @param commandId 协议命令 ID
 * @param outcome 协议统一结果码
 * @param groupJid 已确认加入的群 JID
 * @param reasonCode 稳定失败原因码
 * @param reasonMessage 脱敏失败说明
 * @param retryable 协议层是否认为失败可恢复，仅用于结果分类，不触发重投
 * @param occurredAt 协议结果发生时间
 */
public record PullTaskManagerJoinCallback(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        String commandId,
        PullTaskManagerJoinProtocolOutcome outcome,
        String groupJid,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long occurredAt
) {
    /** 校验回调业务关联和命令定位键。 */
    public PullTaskManagerJoinCallback {
        if (!positive(tenantId) || !positive(pullTaskId)
                || !positive(groupExecutionId) || !positive(actionId)) {
            throw new IllegalArgumentException("管理员进群回调业务关联非法");
        }
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("管理员进群回调 commandId 不能为空");
        }
        commandId = commandId.trim();
        outcome = Objects.requireNonNull(outcome, "管理员进群回调 outcome 不能为空");
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
