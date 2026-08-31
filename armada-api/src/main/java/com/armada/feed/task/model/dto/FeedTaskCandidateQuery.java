package com.armada.feed.task.model.dto;

import com.armada.account.model.dto.AccountQuery;

/** 动态发布任务账号候选游标查询。 */
public class FeedTaskCandidateQuery extends AccountQuery {

    private Long taskId;
    private int limit;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
}
