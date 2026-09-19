package com.armada.account.model.enums;
/** 互存任务生命周期，停止后仍接收在途回执。 */
public enum AccountMutualContactTaskStatus {
    /** 有待执行或已提交操作。 */ RUNNING(1),
    /** 用户停止新派发。 */ STOPPED(2),
    /** 所有方向成功。 */ COMPLETED(3),
    /** 存在失败、待确认或被待确认阻塞的方向。 */ ATTENTION(4);
    private final int code;
    AccountMutualContactTaskStatus(int code) {
        this.code = code;
    }
    public int code() {
        return code;
    }
}
