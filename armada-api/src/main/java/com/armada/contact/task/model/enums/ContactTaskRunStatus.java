package com.armada.contact.task.model.enums;

/** 通讯录营销任务运行状态，取值与竞品一致。 */
public enum ContactTaskRunStatus {

    /** 未开始。 */
    NOT_STARTED(0),
    /** 进行中。 */
    RUNNING(1),
    /** 已完成。 */
    COMPLETED(2),
    /** 已暂停，可恢复。 */
    PAUSED(3),
    /** 已停止，终态不可恢复。 */
    STOPPED(4);

    private final int code;

    ContactTaskRunStatus(int code) {
        this.code = code;
    }

    /**
     * 落库与接口使用的状态码。
     *
     * @return 状态码
     */
    public int code() {
        return code;
    }

    /**
     * 由状态码解析枚举。
     *
     * @param code 状态码
     * @return 对应枚举
     * @throws IllegalArgumentException 状态码非法时抛出
     */
    public static ContactTaskRunStatus fromCode(int code) {
        for (ContactTaskRunStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的通讯录任务运行状态: " + code);
    }
}
