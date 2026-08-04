package com.armada.task.model.enums;

/** 单次批量加成员调用的状态；与 pull_task_pull_call.call_status 一一对应。 */
public enum PullTaskPullCallStatus {

    /** 计划：调用行、料子绑定和站台绑定已在同一事务内写入，协议命令尚未发出。
     *  崩溃恢复时看到这个状态要用原 idempotency_key 重投，不得重新分配料子。 */
    PLANNED(1),
    /** 已提交：批量加成员命令已发出。 */
    SUBMITTED(2),
    /** 已回写：逐参与者结果已落到料子行和站台行。 */
    WRITTEN_BACK(3),
    /** 结果未知：由查询或回调收敛。 */
    UNKNOWN(4),
    /** 取消：任务结束时尚未发出的调用。 */
    CANCELED(5);

    private final int code;

    PullTaskPullCallStatus(int code) {
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
