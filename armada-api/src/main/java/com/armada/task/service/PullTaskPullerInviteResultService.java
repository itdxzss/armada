package com.armada.task.service;

import com.armada.task.model.dto.PullTaskPullerInviteCallback;

/** 收敛管理员邀请拉手协议结果。 */
public interface PullTaskPullerInviteResultService {

    /**
     * 应用一条邀请结果。
     *
     * @param callback 强关联邀请结果
     * @return true 表示属于当前动作；false 表示关联不匹配或迟到
     */
    boolean apply(PullTaskPullerInviteCallback callback);
}
