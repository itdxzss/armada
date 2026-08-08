package com.armada.platform.protocol.model.enums;

/**
 * 协议后端标识。
 *
 * <p>Armada 用该枚举区分同一类业务命令应发送到 Web 协议层还是 Android 协议层。
 * 空值和未知值默认视为 Web,避免存量账号因未设置 protocol_id 而改变上线链路。</p>
 */
public enum ProtocolBackend {

    /** Baileys Web 协议层。 */
    WEB,

    /** Android feature server 协议层。 */
    ANDROID;

    /**
     * 从账号的 protocol_id 推导协议后端。
     *
     * @param protocolId 账号表 protocol_id
     * @return 协议后端;空值或未知值返回 {@link #WEB}
     */
    public static ProtocolBackend fromProtocolId(String protocolId) {
        if (protocolId == null || protocolId.isBlank()) {
            return WEB;
        }
        String normalized = protocolId.trim();
        for (ProtocolBackend backend : values()) {
            if (backend.name().equalsIgnoreCase(normalized)) {
                return backend;
            }
        }
        return WEB;
    }

    /**
     * 严格解析必须显式配置的协议后端；用于新建普群等跨后端副作用链路。
     *
     * @param protocolId 账号表 protocol_id
     * @return 显式 WEB 或 ANDROID
     * @throws IllegalArgumentException 空值或未知值
     */
    public static ProtocolBackend fromExplicitProtocolId(String protocolId) {
        if (protocolId == null || protocolId.isBlank()) {
            throw new IllegalArgumentException("protocol_id 必须显式配置为 WEB 或 ANDROID");
        }
        String normalized = protocolId.trim();
        for (ProtocolBackend backend : values()) {
            if (backend.name().equalsIgnoreCase(normalized)) {
                return backend;
            }
        }
        throw new IllegalArgumentException("不支持的 protocol_id: " + normalized);
    }
}
