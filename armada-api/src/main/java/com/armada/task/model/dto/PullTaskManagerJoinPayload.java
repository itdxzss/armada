package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;

/**
 * 管理员踩链接所需的协议参数与租约快照。
 *
 * @param account         选中的管理账号
 * @param inviteLink      归一化群链接
 * @param operationId     稳定的业务操作标识
 * @param lockOwner       当前执行行租约持有者
 * @param expectedVersion 结果回写使用的乐观锁版本
 * @param knownGroupJid   重启恢复时已知的群 JID；首次提交时为空
 */
public record PullTaskManagerJoinPayload(
        ProtocolAccountRef account,
        String inviteLink,
        String operationId,
        String lockOwner,
        int expectedVersion,
        String knownGroupJid) {

    /** 首次提交动作时还没有可用于实时复核的群 JID。 */
    public PullTaskManagerJoinPayload(
            ProtocolAccountRef account,
            String inviteLink,
            String operationId,
            String lockOwner,
            int expectedVersion) {
        this(account, inviteLink, operationId, lockOwner, expectedVersion, null);
    }
}
