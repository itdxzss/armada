package com.armada.task.service;

/** 普通群链接任务中单条群执行行的人工生命周期操作。 */
public interface PullTaskStandardExecutionLifecycleService {

    /** 暂停指定执行行；重复暂停幂等。 */
    void pause(long taskId, long executionId);

    /** 恢复指定人工暂停执行行；重复恢复幂等。 */
    void resume(long taskId, long executionId);

    /** 永久放弃指定执行行；重复结束幂等。 */
    void end(long taskId, long executionId);
}
