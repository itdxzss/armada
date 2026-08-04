package com.armada.task.model.enums;

/** 群链接执行行状态；与 pull_task_group_execution.execution_status 的 TINYINT 取值一一对应。 */
public enum PullTaskExecutionStatus {

    /** 草稿：创建页未提交的计划行，不参与调度，不占用群链接。 */
    DRAFT(0),
    /** 待启动：已随任务冻结，等待调度取走。 */
    WAIT_START(1),
    /** 执行中：已被调度器抢占，正在推进业务阶段。 */
    EXECUTING(2),
    /** 等待资源：管理员、拉手或站台不足，暂停本行等待补充。 */
    WAIT_RESOURCE(3),
    /** 已完成：本行全部料子进入终态并收口。 */
    COMPLETED(4),
    /** 失败终态：链接失效等不可恢复原因。 */
    FAILED(5),
    /** 已放弃：人工放弃该群，不可恢复。 */
    ABANDONED(6);

    private final int code;

    PullTaskExecutionStatus(int code) {
        this.code = code;
    }

    /**
     * 数据库存储值。
     *
     * @return TINYINT 取值
     */
    public int code() {
        return code;
    }
}
