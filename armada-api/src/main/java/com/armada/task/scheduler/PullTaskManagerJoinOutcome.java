package com.armada.task.scheduler;

/**
 * 管理员踩链接与实时在群复核后的业务结果。
 *
 * @param kind          结果类型
 * @param groupJid      协议返回的群 JID
 * @param reasonCode    稳定原因码
 * @param reasonMessage 可展示的脱敏原因
 */
public record PullTaskManagerJoinOutcome(
        Kind kind,
        String groupJid,
        String reasonCode,
        String reasonMessage) {

    /** 管理员入群结果分类。 */
    public enum Kind {
        CONFIRMED,
        MANAGER_FAILED,
        EXECUTION_FAILED,
        UNCONFIRMED
    }

    /** @return 已通过实时成员列表确认在群 */
    public static PullTaskManagerJoinOutcome confirmed(String groupJid) {
        if (groupJid == null || groupJid.isBlank()) {
            throw new IllegalArgumentException("确认管理员在群时 groupJid 不能为空");
        }
        return new PullTaskManagerJoinOutcome(
                Kind.CONFIRMED, groupJid.trim(), null, null);
    }

    /** @return 协议明确失败 */
    public static PullTaskManagerJoinOutcome managerFailed(String reasonCode) {
        return new PullTaskManagerJoinOutcome(
                Kind.MANAGER_FAILED, null, reasonCode, "管理员进群失败");
    }

    /** @return 群链接或目标群已明确不可用，执行行应进入失败终态 */
    public static PullTaskManagerJoinOutcome executionFailed(String reasonCode) {
        return new PullTaskManagerJoinOutcome(
                Kind.EXECUTION_FAILED, null, reasonCode, "管理员进群失败");
    }

    /** @return 协议结果或实时在群状态无法确认 */
    public static PullTaskManagerJoinOutcome unconfirmed(String groupJid, String reasonCode) {
        return new PullTaskManagerJoinOutcome(
                Kind.UNCONFIRMED, groupJid, reasonCode, "管理员在群结果无法确认");
    }
}
