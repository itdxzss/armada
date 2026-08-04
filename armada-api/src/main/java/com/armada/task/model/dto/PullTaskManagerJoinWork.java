package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;

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

    /** @return 使用同一账号复核目标群实时成员的查询 */
    public GroupMemberListQuery memberListQuery(String groupJid) {
        return new GroupMemberListQuery(
                payload.account(), groupJid, payload.operationId() + ":verify");
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
