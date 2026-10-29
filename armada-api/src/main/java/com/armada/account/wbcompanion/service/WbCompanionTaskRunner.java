package com.armada.account.wbcompanion.service;

/** 按步推进登录到 WB 分身的任务，由调度器调用；调用方负责设置任务所属租户。 */
public interface WbCompanionTaskRunner {

    /**
     * 推进一步：抢到租约后只做当前这一条的下一小步（WB 发起、安卓输入配对码或查询 WB 结果），不在一次调用里等待。
     *
     * @param taskId 任务 ID
     */
    void advance(Long taskId);
}
