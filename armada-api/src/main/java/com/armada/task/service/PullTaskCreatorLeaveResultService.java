package com.armada.task.service;

import com.armada.task.model.dto.PullTaskCreatorLeaveCallback;

/** 收敛标准拉人任务群主退群协议结果。 */
public interface PullTaskCreatorLeaveResultService {

    /**
     * 应用一条群主退群链路结果。
     *
     * @param callback 已通过平台层契约校验的结果
     * @return true 表示结果属于当前动作；false 表示关联不匹配或迟到
     */
    boolean apply(PullTaskCreatorLeaveCallback callback);
}
