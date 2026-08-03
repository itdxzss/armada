package com.armada.task.model.enums;

/** 账号动作结果；与 pull_task_account_action.action_status 一一对应。 */
public enum PullTaskActionStatus {

    /** 待执行：动作行已建，命令尚未发出。 */
    PENDING(1),
    /** 已提交：协议命令已发出，等待结果。 */
    SUBMITTED(2),
    /** 成功：协议确认成功。 */
    SUCCESS(3),
    /** 失败：明确失败，终态；加好友失败不阻断后续邀请或拉人。 */
    FAILED(4),
    /** 结果未知：由查询或回调收敛，不得伪装成成功或失败。 */
    UNKNOWN(5),
    /** 取消：任务结束时尚未发出的动作。 */
    CANCELED(6);

    private final int code;

    PullTaskActionStatus(int code) {
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
