package com.armada.group.service;

import com.armada.account.model.entity.AccountStateCode;
import java.util.List;

/**
 * 群操作可用账号的生命周期状态口径。
 *
 * <p>判断账号"能不能干活"的是 {@code login_state}(在线与否);{@code account_state} 记的是账号
 * 生命周期处境。被抢登与抢登中都是登录竞争的中间态,协议连接可能仍然健康,把它们排除等于
 * 让一个在线号在群组列表里显示为不可用、在选号时被跳过。</p>
 *
 * <p>与 {@code MarketingAccountEligibility} 同口径。封禁/导出/解绑/受限是终态或禁用态,
 * 不纳入;新增态尚未上报过协议状态,也不纳入。</p>
 */
public final class GroupExecutableAccountStates {

    /**
     * 允许承担群操作的账号生命周期状态:正常、被抢登、抢登中。
     */
    private static final List<Integer> EXECUTABLE = List.of(
            AccountStateCode.NORMAL,
            AccountStateCode.LOGIN_REPLACED,
            AccountStateCode.TAKING_OVER);

    private GroupExecutableAccountStates() {
    }

    /**
     * 返回传给选号 SQL 的只读账号状态集合。
     *
     * @return 正常、被抢登、抢登中三态
     */
    public static List<Integer> executable() {
        return EXECUTABLE;
    }
}
