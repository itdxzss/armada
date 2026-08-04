package com.armada.task.service;

import com.armada.task.model.dto.PullTaskContactSaveCallback;

/** 收敛联系人保存协议结果并唤醒对应执行行。 */
public interface PullTaskContactSaveResultService {

    /**
     * 应用一条联系人保存结果。
     *
     * @param callback 强关联协议结果
     * @return true 表示结果属于当前动作；false 表示关联不匹配或迟到
     */
    boolean apply(PullTaskContactSaveCallback callback);
}
