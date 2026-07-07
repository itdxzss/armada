package com.armada.platform.protocol.process;

public record ProcessCommandResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut
) {
}
