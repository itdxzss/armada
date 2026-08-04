package com.armada.task.service;

/** 普通群链接任务手动与自动启动的统一业务入口。 */
public interface PullTaskStandardStartService {

    /**
     * 把当前租户的待启动任务推进为执行中；重复启动幂等。
     *
     * @param taskId 拉群任务 ID
     */
    void start(long taskId);
}
