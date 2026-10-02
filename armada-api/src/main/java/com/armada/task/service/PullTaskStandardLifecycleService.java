package com.armada.task.service;

/** 普通群链接父任务的人工操作与群资源耗尽收口。 */
public interface PullTaskStandardLifecycleService {

    /** 暂停执行中的任务；重复暂停幂等。 */
    void pause(long taskId);

    /** 恢复人工暂停的任务；重复恢复幂等。 */
    void resume(long taskId);

    /** 永久结束执行中或人工暂停的任务；重复结束幂等。 */
    void end(long taskId);

    /** 群资源耗尽时永久结束仍在执行中的任务，并复用任务级取消与资源释放流程。 */
    void endForGroupExhaustion(long taskId);
}
