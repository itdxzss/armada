package com.armada.platform.protocol.process;

public record ProtocolRestartProcessVO(
        String processName,
        String readyUrl,
        boolean ready,
        Integer statusCode,
        String error,
        long checkedAt
) {
}
