package com.armada.marketing.service.impl;

import com.armada.group.model.enums.AccountGroupMembershipStatus;

/**
 * 营销发送前的账号群关系决策策略。
 *
 * <p>本类只执行无副作用的状态映射：{@code IN_GROUP}、{@code UNCONFIRMED} 允许发送，
 * {@code KICKED_OUT}、{@code LEFT}、{@code NOT_IN_GROUP} 返回稳定原因码的业务跳过决策。
 * 空状态按旧数据兼容口径视为 {@code UNCONFIRMED}，不得因关系行缺失误阻断历史目标。</p>
 */
public final class MarketingMembershipSendPolicy {

    private MarketingMembershipSendPolicy() {
    }

    /**
     * 将当前账号群关系转换为营销发送决策。
     *
     * @param status 当前账号群关系状态；为空时按 {@code UNCONFIRMED} 处理
     * @return 可发送决策，或携带稳定原因码和运营可读原因的跳过决策
     */
    public static Decision decide(AccountGroupMembershipStatus status) {
        AccountGroupMembershipStatus resolved = status == null
                ? AccountGroupMembershipStatus.UNCONFIRMED : status;
        return switch (resolved) {
            case IN_GROUP, UNCONFIRMED -> Decision.send();
            case KICKED_OUT -> Decision.skip("KICKED_OUT", "账号已被踢出群聊");
            case LEFT -> Decision.skip("LEFT", "账号已主动退出群聊");
            case NOT_IN_GROUP -> Decision.skip("NOT_IN_GROUP", "账号当前已不在群聊");
        };
    }

    /**
     * 单个实际群目标的发送决策。
     *
     * @param sendable 是否允许生成协议发送命令
     * @param reasonCode 业务跳过的稳定原因码；允许发送时为空
     * @param reasonMessage 业务跳过的运营可读原因；允许发送时为空
     */
    public record Decision(boolean sendable, String reasonCode, String reasonMessage) {
        static Decision send() {
            return new Decision(true, null, null);
        }

        static Decision skip(String code, String message) {
            return new Decision(false, code, message);
        }
    }
}
