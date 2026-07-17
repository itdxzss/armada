package com.armada.platform.protocol.model.result;

/**
 * 与具体协议后端无关的账号运行态。
 *
 * <p>该结果只表达协议后端当前返回的运行状态，不代表 Armada 已经把状态同步到账号状态表。</p>
 *
 * @param state 协议后端返回并归一化后的状态名称
 */
public record ProtocolAccountRuntimeStatus(String state) {

    private static final String UNKNOWN_STATE = "UNKNOWN";
    private static final String ONLINE_STATE = "ONLINE";

    /**
     * 归一化协议后端返回的状态名称。
     */
    public ProtocolAccountRuntimeStatus {
        state = state == null ? UNKNOWN_STATE : state.trim();
    }

    /**
     * 判断账号是否明确在线。
     *
     * @return 状态为 ONLINE 时返回 true
     */
    public boolean online() {
        return ONLINE_STATE.equalsIgnoreCase(state);
    }
}
