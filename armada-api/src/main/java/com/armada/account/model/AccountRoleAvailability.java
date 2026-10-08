package com.armada.account.model;

import com.armada.account.model.entity.AccountLoginStateCode;

/**
 * 任务角色账号的连接可用性；ONLINE 仍须经过各角色的既有执行资格检查。
 *
 * @param accountId 当前租户内未软删账号
 * @param kind 当前可用性
 * @param offlineSince 本次连续离线开始时间，未上报时可为空
 * @param loginState 实际登录状态，未上报时可为空
 * @param reservation 账号或全局身份的预留归属，恢复前必须复核其租户、任务和执行行
 */
public record AccountRoleAvailability(Long accountId, Kind kind, Long offlineSince,
                                      Integer loginState, AccountCreatorReservation reservation) {

    /** @return 协议明确报告离线；任务域无需依赖账号持久化状态码 */
    public boolean offline() {
        return Integer.valueOf(AccountLoginStateCode.OFFLINE).equals(loginState);
    }

    /** 任务等待策略使用的连接可用性。 */
    public enum Kind {
        /** 已连接；禁言等操作权限继续交由角色资格查询检查。 */
        ONLINE,
        /** 暂时离线或待上线，可在当前角色宽限期内等待。 */
        RECOVERING,
        /** 生命周期、人工意图或恢复条件不允许继续等待此账号。 */
        TERMINAL
    }
}
