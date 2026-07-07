package com.armada.platform.protocol.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolProcessRestartServiceImplTest {

    @Test
    void restart_runsFixedPm2CommandAndReturnsSuccessWhenAllProcessesBecomeReady() {
        ProtocolRestartProperties properties = properties();
        FakeRunner runner = new FakeRunner(new ProcessCommandResult(0, "ok", "", false));
        FakeProbe probe = new FakeProbe(List.of(
                new ReadyProbeResult("http://127.0.0.1:8080/readyz", true, 200, null),
                new ReadyProbeResult("http://127.0.0.1:8081/readyz", true, 200, null)));
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, runner, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isTrue();
        assertThat(result.command()).isEqualTo("pm2 restart protocol-master protocol-worker-1 --update-env");
        assertThat(result.processes()).hasSize(2);
        assertThat(result.processes()).allMatch(ProtocolRestartProcessVO::ready);
        assertThat(result.message()).isEqualTo("协议进程已重启");
        assertThat(runner.commands).containsExactly(List.of(
                "pm2", "restart", "protocol-master", "protocol-worker-1", "--update-env"));
    }

    @Test
    void restart_returnsFailureWhenPm2CommandFails() {
        ProtocolRestartProperties properties = properties();
        FakeRunner runner = new FakeRunner(new ProcessCommandResult(1, "", "process not found", false));
        FakeProbe probe = new FakeProbe(List.of());
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, runner, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("PM2 重启失败");
        assertThat(result.message()).contains("exitCode=1");
        assertThat(result.message()).contains("process not found");
        assertThat(result.processes()).isEmpty();
        assertThat(probe.urls).isEmpty();
    }

    @Test
    void restart_returnsFailureWhenPm2CommandTimesOut() {
        ProtocolRestartProperties properties = properties();
        FakeRunner runner = new FakeRunner(new ProcessCommandResult(-1, "", "", true));
        FakeProbe probe = new FakeProbe(List.of());
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, runner, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("PM2 重启超时");
        assertThat(result.processes()).isEmpty();
    }

    @Test
    void restart_returnsFailureWhenAnyReadyUrlDoesNotBecomeReady() {
        ProtocolRestartProperties properties = properties();
        properties.setReadyTimeoutMs(0);
        FakeRunner runner = new FakeRunner(new ProcessCommandResult(0, "ok", "", false));
        FakeProbe probe = new FakeProbe(List.of(
                new ReadyProbeResult("http://127.0.0.1:8080/readyz", true, 200, null),
                new ReadyProbeResult("http://127.0.0.1:8081/readyz", false, 503, "not_ready")));
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, runner, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("协议进程未全部 ready");
        assertThat(result.processes()).hasSize(2);
        assertThat(result.processes().get(0).ready()).isTrue();
        assertThat(result.processes().get(1).ready()).isFalse();
        assertThat(result.processes().get(1).statusCode()).isEqualTo(503);
    }

    private static ProtocolRestartProperties properties() {
        ProtocolRestartProperties properties = new ProtocolRestartProperties();
        properties.setProcessNames(List.of("protocol-master", "protocol-worker-1"));
        properties.setReadyUrls(List.of(
                "http://127.0.0.1:8080/readyz",
                "http://127.0.0.1:8081/readyz"));
        properties.setReadyPollIntervalMs(0);
        properties.setReadyTimeoutMs(1);
        properties.setReadyRequestTimeoutMs(1);
        return properties;
    }

    private static final class FakeRunner implements ProtocolProcessCommandRunner {

        private final ProcessCommandResult result;
        private final List<List<String>> commands = new ArrayList<>();

        private FakeRunner(ProcessCommandResult result) {
            this.result = result;
        }

        @Override
        public ProcessCommandResult run(List<String> command, Duration timeout) {
            commands.add(command);
            return result;
        }
    }

    private static final class FakeProbe implements ProtocolReadyProbe {

        private final List<ReadyProbeResult> results;
        private final List<String> urls = new ArrayList<>();

        private FakeProbe(List<ReadyProbeResult> results) {
            this.results = results;
        }

        @Override
        public ReadyProbeResult probe(String readyUrl, Duration timeout) {
            urls.add(readyUrl);
            return results.stream()
                    .filter(result -> result.readyUrl().equals(readyUrl))
                    .findFirst()
                    .orElse(new ReadyProbeResult(readyUrl, false, null, "missing fake result"));
        }
    }
}
