package com.armada.platform.registration.go;

/** Go 注册事务状态；取值来自 Go 合同 Data.state，未识别的值归为 UNRECOGNIZED，调用方按结果不明处理。 */
public enum GoRegistrationState {
    /** 事务已创建，尚未真正发出请求。 */ PREPARED,
    /** 发码请求在途。 */ REQUESTING_CODE,
    /** 已发码，等待验证码提交。 */ WAITING_CODE,
    /** 外部操作结果不明，Go 为避免重复发码已停止。 */ OUTCOME_UNKNOWN,
    /** 需要人工处理。 */ ACTION_REQUIRED,
    /** 本地已取消。 */ CANCELLED,
    /** 验码请求在途。 */ VERIFYING,
    /** 注册完成，是否可用还要看 result_available。 */ REGISTERED,
    /** 响应缺失或不是已知状态。 */ UNRECOGNIZED;

    /** @param wire Go 响应里的状态字符串 @return 对应状态，空或未知返回 UNRECOGNIZED */
    public static GoRegistrationState fromWire(String wire) {
        for (var state : values()) { if (state.name().equals(wire)) { return state; } }
        return UNRECOGNIZED;
    }
}
