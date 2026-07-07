package com.armada.platform.protocol.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultProtocolProcessCommandRunnerTest {

    @Test
    void run_executesCommandAndCapturesStdout() {
        DefaultProtocolProcessCommandRunner runner = new DefaultProtocolProcessCommandRunner();

        ProcessCommandResult result = runner.run(
                List.of("/bin/sh", "-c", "printf protocol-restart-ok"),
                Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).isEqualTo("protocol-restart-ok");
        assertThat(result.stderr()).isEmpty();
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void run_returnsTimeoutResultWhenCommandExceedsTimeout() {
        DefaultProtocolProcessCommandRunner runner = new DefaultProtocolProcessCommandRunner();

        ProcessCommandResult result = runner.run(
                List.of("/bin/sh", "-c", "sleep 2"),
                Duration.ofMillis(50));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timedOut()).isTrue();
    }
}
