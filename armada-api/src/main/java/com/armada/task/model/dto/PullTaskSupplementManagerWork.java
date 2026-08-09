package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.task.model.enums.PullTaskSupplementManagerOperation;

/** 已持久化单步状态、可在事务外执行的补充管理员工作项。 */
public record PullTaskSupplementManagerWork(
        Long tenantId,
        Long executionId,
        Long targetGroupAccountId,
        Long actionId,
        PullTaskSupplementManagerPayload payload) {

    /** @return 当前协议动作 */
    public PullTaskSupplementManagerOperation operation() {
        return payload.operation();
    }

    /** @return 冻结的协议发起账号 */
    public ProtocolAccountRef actor() {
        return payload.accounts().actor();
    }

    /** @return 被补充的目标账号 */
    public ProtocolAccountRef target() {
        return payload.accounts().target();
    }

    /** @return 踩链接命令 */
    public GroupJoinCommand joinCommand() {
        return new GroupJoinCommand(
                target(), payload.group().inviteLink(), payload.group().operationId());
    }

    /** @return 目标群 JID */
    public String groupJid() {
        return payload.group().groupJid();
    }

    /** @return 目标账号的用户 JID */
    public String targetJid() {
        return WhatsappJids.userJid(target().wsPhone());
    }

    /** @return 只允许查询收敛，不得重放协议命令 */
    public boolean verificationOnly() {
        return payload.verificationOnly();
    }

    /** @return 当前租约持有者 */
    public String lockOwner() {
        return payload.lease().lockOwner();
    }

    /** @return 结果回写期望的执行行版本 */
    public int expectedVersion() {
        return payload.lease().expectedVersion();
    }
}
