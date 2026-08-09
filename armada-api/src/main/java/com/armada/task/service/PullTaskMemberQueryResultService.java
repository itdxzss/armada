package com.armada.task.service;

import com.armada.task.model.dto.PullTaskMemberQueryCallback;

/** 收敛异步成员查询结果并唤醒对应处理器。 */
public interface PullTaskMemberQueryResultService {

    /** @return true 表示属于当前或已完成尝试；false 表示关联不匹配或已经迟到 */
    boolean apply(PullTaskMemberQueryCallback callback);
}
