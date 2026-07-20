package com.armada.marketing.service.impl;

import com.armada.account.model.entity.AccountStateCode;
import java.util.List;

/**
 * 普通营销任务发送账号候选状态策略。
 */
final class MarketingAccountEligibility {

    /**
     * 普通营销任务允许使用的账号生命周期状态。
     */
    private static final List<Integer> SELECTABLE_ACCOUNT_STATES = List.of(
            AccountStateCode.NORMAL,
            AccountStateCode.LOGIN_REPLACED,
            AccountStateCode.TAKING_OVER);

    private MarketingAccountEligibility() {
    }

    /**
     * 判断账号生命周期状态是否允许进入普通营销任务。
     *
     * @param accountState 账号生命周期状态
     * @return 正常、被抢登或抢登中时返回 true
     */
    static boolean supportsAccountState(Integer accountState) {
        return SELECTABLE_ACCOUNT_STATES.contains(accountState);
    }

    /**
     * 返回传给创建候选 SQL 的只读账号状态集合。
     *
     * @return 普通营销任务允许的账号生命周期状态
     */
    static List<Integer> selectableAccountStates() {
        return SELECTABLE_ACCOUNT_STATES;
    }
}
