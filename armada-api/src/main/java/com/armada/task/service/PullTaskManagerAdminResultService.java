package com.armada.task.service;

import com.armada.task.model.dto.PullTaskManagerAdminCallback;

/** 收敛任务管理员提权协议结果。 */
public interface PullTaskManagerAdminResultService {

    /**
     * 应用一条管理员设置结果。
     *
     * @param callback 强关联管理员设置结果
     * @return true 表示属于当前尝试；false 表示关联不匹配或迟到
     */
    boolean apply(PullTaskManagerAdminCallback callback);
}
