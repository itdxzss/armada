package com.armada.task.model.enums;

/** 料子号码的入群结果；与 pull_task_material_member.pull_status 一一对应。 */
public enum PullTaskMaterialPullStatus {

    /** 未消费：尚未被任何一次拉人调用取走，是料子游标的判定依据。 */
    UNCONSUMED(0),
    /** 已提交：已随批量加成员命令发出，等待协议结果。 */
    SUBMITTED(1),
    /** 成功：协议确认已入群。 */
    SUCCESS(2),
    /** 失败：协议明确失败，终态，不重试、不换拉手。 */
    FAILED(3),
    /** 结果未知：只能由状态查询或协议回调收敛，不得伪装成成功或失败。 */
    UNKNOWN(4),
    /** 取消：任务结束时尚未发出的动作。 */
    CANCELED(5);

    private final int code;

    PullTaskMaterialPullStatus(int code) {
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
