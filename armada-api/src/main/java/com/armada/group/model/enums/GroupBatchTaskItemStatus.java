package com.armada.group.model.enums;

/** 群组列表批量任务明细状态。 */
public enum GroupBatchTaskItemStatus {

    /** 待执行：尚未取得该群的执行结果。 */
    PENDING(1),

    /** 成功：该群操作成功并已回填，终态。 */
    SUCCESS(2),

    /** 失败：该群操作失败，保留旧数据并记录原因，终态。 */
    FAILED(3);

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
