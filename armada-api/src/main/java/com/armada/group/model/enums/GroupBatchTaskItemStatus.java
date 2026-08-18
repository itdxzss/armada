package com.armada.group.model.enums;

/** 群组列表批量任务明细状态。 */
public enum GroupBatchTaskItemStatus {

    /** 待执行：尚未取得该群的执行结果。 */
    PENDING(1),

    /** 成功：该群操作成功并已回填，终态。 */
    SUCCESS(2),

    /** 失败：该群操作失败，保留旧数据并记录原因，终态。 */
    FAILED(3),

    /** 已取消：任务被取消前该项还没开始执行，终态；不计入成功数也不计入失败数。 */
    CANCELED(4),

    /** 已写入 Outbox，等待 dispatcher 获得 broker ACK。 */
    DISPATCHED(5),

    /** 命令已派发，等待协议端事实事件与结算事件。 */
    WAITING_RESULT(6);

    private final int code;

    GroupBatchTaskItemStatus(int code) {
        this.code = code;
    }

    /** 返回稳定数据库码。 */
    public int code() {
        return code;
    }

    /**
     * 按稳定数据库码解析明细状态。
     *
     * @param code 数据库码
     * @return 对应状态
     * @throws IllegalArgumentException 未知码
     */
    public static GroupBatchTaskItemStatus fromCode(Integer code) {
        if (code != null) {
            for (GroupBatchTaskItemStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("未知群批量任务明细状态: " + code);
    }
}
