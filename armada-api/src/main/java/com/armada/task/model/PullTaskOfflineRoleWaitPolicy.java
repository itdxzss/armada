package com.armada.task.model;

import com.armada.account.model.AccountRoleAvailability;

/** 依据账号真实连接事实和本次连续离线起点，决定角色是否继续占用任务名额。 */
public final class PullTaskOfflineRoleWaitPolicy {
    private PullTaskOfflineRoleWaitPolicy() { }

    /**
     * 不刷新离线计时；上线立即可用，不可恢复或到期立即放弃。
     * @param availability 账号域提供的可用性，账号缺失时为 null
     * @param graceMs 角色配置的宽限时长，单位毫秒
     * @param now 当前时间，单位毫秒
     * @return 使用、等待至固定截止时间或放弃此角色
     */
    public static Decision decide(AccountRoleAvailability availability, long graceMs, long now) {
        if (availability == null || availability.kind() == AccountRoleAvailability.Kind.TERMINAL) {
            return new Decision(Kind.GIVE_UP, null);
        }
        if (availability.kind() == AccountRoleAvailability.Kind.ONLINE) {
            return new Decision(Kind.USE, null);
        }
        if (availability.offlineSince() == null) {
            return new Decision(Kind.GIVE_UP, null);
        }
        long deadline = availability.offlineSince() + graceMs;
        return now < deadline ? new Decision(Kind.WAIT, deadline) : new Decision(Kind.GIVE_UP, null);
    }

    /** 账号连接事实的角色等待判定；USE 仍须经过角色既有的业务资格过滤。 */
    public enum Kind { USE, WAIT, GIVE_UP }

    /** @param kind 判定结果 @param waitUntil 仅 WAIT 时为离线宽限截止时间 */
    public record Decision(Kind kind, Long waitUntil) { }
}
