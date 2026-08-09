package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.GroupJoinCommand;

/**
 * 已持久化角色账号和踩链接动作、可在事务外调用协议层的工作项。
 *
 * @param tenantId       所属租户 ID
 * @param executionId    执行行 ID
 * @param groupAccountId 管理角色行 ID
 * @param actionId       踩链接动作行 ID
 * @param payload        协议参数与租约快照
 */
public record PullTaskManagerJoinWork(
        Long tenantId,
        Long executionId,
        Long groupAccountId,
        Long actionId,
        PullTaskManagerJoinPayload payload) {

    /** @return 统一进群命令 */
    public GroupJoinCommand joinCommand() {
        return new GroupJoinCommand(
                payload.account(), payload.inviteLink(), payload.operationId());
    }

    /** @return 当前租约持有者 */
    public String lockOwner() {
        return payload.lockOwner();
    }

    /** @return 结果回写期望的执行行版本 */
    public int expectedVersion() {
        return payload.expectedVersion();
    }
}
