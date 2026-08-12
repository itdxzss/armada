package com.armada.group.model.enums;

/** 群组列表批量任务主状态。 */
public enum GroupBatchTaskStatus {

    /** 待执行：任务与明细已落库，尚无明细开始执行。 */
    PENDING(1),

    /** 运行中：至少一项已开始执行，仍有明细未终结。 */
    RUNNING(2),

    /** 已完成：全部明细成功或失败并完成聚合，终态。 */
    COMPLETED(3),

    /** 任务失败：任务级异常导致整体无法执行，终态。 */
    FAILED(4);

    private final int code;

    GroupBatchTaskStatus(int code) {
        this.code = code;
    }

    /** 返回稳定数据库码。 */
    public int code() {
        return code;
    }

    /** 判定终态；前端据此停止轮询。 */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * 按稳定数据库码解析任务主状态。
     *
     * @param code 数据库码
     * @return 对应状态
     * @throws IllegalArgumentException 未知码
     */
    public static GroupBatchTaskStatus fromCode(Integer code) {
        if (code != null) {
            for (GroupBatchTaskStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("未知群批量任务状态: " + code);
    }
}
