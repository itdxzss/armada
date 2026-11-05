package com.armada.platform.protocol.model.result;

/** 档位查询归一化结果；禁止携带协议响应原文。 */
public record ProtocolAccountIntegrity(Status status, String userType, String trustTier,
        Boolean isSuspicious, Boolean isBanned, Boolean isMvSubscribed, Long joinDateMs,
        String phoneCountryCode, String reasonCode) {
    /** 协议契约状态。 */
    public enum Status {
        /** 至少一个 integrity 字段有效。 */ OK,
        /** 成功返回但字段为空，疑似限流。 */ EMPTY,
        /** 服务器明确不可查询。 */ NOT_ALLOWED,
        /** 固定原因码描述的查询失败。 */ ERROR
    }
    /** 构造没有任何档位字段的安全失败结果。 */
    public static ProtocolAccountIntegrity failure(String code) {
        return new ProtocolAccountIntegrity(Status.ERROR, null, null, null, null, null, null, null, code);
    }
}
