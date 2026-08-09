package com.armada.task.model.enums;

/** 普通拉群异步成员查询状态。 */
public enum PullTaskMemberQueryStatus {

    /** 命令已创建，等待协议结果。 */
    PENDING(1),
    /** 查询成功，结果快照可读取。 */
    SUCCEEDED(2),
    /** 协议明确返回查询失败。 */
    FAILED(3),
    /** 截止时间内未收到结果。 */
    EXPIRED(4),
    /** 任务或执行行结束时取消。 */
    CANCELED(5);

    private final int code;

    PullTaskMemberQueryStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
