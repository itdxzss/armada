package com.armada.account.model;

import com.armada.account.model.entity.AccountStateCode;
import java.util.List;

/** 拉群账号生命周期准入；在线、能力限制、协议身份和任务占用仍须独立校验。 */
public final class PullTaskAccountEligibility {

    /** 正常、被抢登和抢登中账号在在线时都可以参与拉群。 */
    public static final List<Integer> ACCOUNT_STATES = List.of(
            AccountStateCode.NORMAL, AccountStateCode.LOGIN_REPLACED, AccountStateCode.TAKING_OVER);

    private PullTaskAccountEligibility() {
    }
}
