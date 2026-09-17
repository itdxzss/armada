package com.armada.account.model.enums;

/** 同一采购聚合的注册执行端，手机流程不进入导入与上线。 */
public enum RegistrationExecutionMode {
    /** 现有协议注册流程。 */ COBALT(1),
    /** iOS 原生注册页面。 */ IOS_DEVICE(2);
    private final int code;
    RegistrationExecutionMode(int code) { this.code = code; }
    /** @return 数据库枚举值 */
    public int code() { return code; }
}
