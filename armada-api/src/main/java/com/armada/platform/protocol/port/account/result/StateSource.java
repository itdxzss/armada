package com.armada.platform.protocol.port.account.result;

/**
 * 协议层状态来源(防腐层语义)。
 *
 * <p>HTTP adapter 将协议层 wire 字符串映射成本枚举;未知值兜底为 {@link #UNKNOWN},
 * 避免魔法串泄进账号编排。</p>
 */
public enum StateSource {

    HEARTBEAT,
    MANUAL_REFRESH,
    TASK_REPORT,
    IMPORT,
    PAIRING,
    RECONNECT,
    UNKNOWN
}
