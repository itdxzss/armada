package com.armada.platform.protocol.process;

import java.util.List;

public record ProtocolRestartVO(
        boolean success,
        String command,
        long startedAt,
        long finishedAt,
        long elapsedMs,
        List<ProtocolRestartProcessVO> processes,
        String message
) {
}
