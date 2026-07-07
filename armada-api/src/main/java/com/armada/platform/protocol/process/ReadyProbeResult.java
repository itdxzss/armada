package com.armada.platform.protocol.process;

public record ReadyProbeResult(
        String readyUrl,
        boolean ready,
        Integer statusCode,
        String error
) {
}
