package com.armada.platform.protocol.risk.model;

import java.util.Locale;
import java.util.Optional;

/** 协议层可分析的固定风控信号及其权威作用域。 */
public enum ProtocolRiskSignal {
    RATE_LIMITED("OPERATION"),
    ACCOUNT_REACHOUT_RESTRICTED("ACCOUNT"),
    CHAT_SUSPENDED("CHAT");

    private final String scopeType;

    ProtocolRiskSignal(String scopeType) {
        this.scopeType = scopeType;
    }

    public String scopeType() {
        return scopeType;
    }

    /** 只接受固定协议码，未知文本不进入风控事实表。 */
    public static Optional<ProtocolRiskSignal> fromCode(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
