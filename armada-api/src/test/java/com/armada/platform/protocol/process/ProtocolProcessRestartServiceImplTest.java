package com.armada.platform.protocol.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolProcessRestartServiceImplTest {

    @Test
    void restart_requestsProtocolLayerRestartAndReturnsSuccessWhenAllProcessesBecomeReady() {
        ProtocolRestartProperties properties = properties();
        String command = "pm2 restart protocol-worker-1 --update-env && pm2 restart protocol-master --update-env";
        FakeRestartClient restartClient = new FakeRestartClient(RemoteProtocolRestartResult.success(
                command,
                "protocol-master",
                List.of("protocol-worker-1"),
                true,
                "protocol process restart scheduled"));
        FakeProbe probe = new FakeProbe(List.of(
                new ReadyProbeResult("http://127.0.0.1:8080/readyz", true, 200, null),
                new ReadyProbeResult("http://127.0.0.1:8081/readyz", true, 200, null)));
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, restartClient, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isTrue();
        assertThat(result.command()).isEqualTo(command);
        assertThat(result.processes()).hasSize(2);
        assertThat(result.processes()).allMatch(ProtocolRestartProcessVO::ready);
        assertThat(result.message()).isEqualTo("协议进程已重启");
        assertThat(restartClient.calls).isEqualTo(1);
    }

    @Test
    void restart_returnsFailureWhenProtocolLayerRejectsRestartRequest() {
        ProtocolRestartProperties properties = properties();
        FakeRestartClient restartClient = new FakeRestartClient(new ProtocolException(
                ProtocolErrorCode.HTTP_ERROR,
                ProtocolException.Metadata.of(500, "PM2_RESTART_FAILED", null, null),
                "pm2 worker restart failed exitCode=1 process not found",
                null));
        FakeProbe probe = new FakeProbe(List.of());
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, restartClient, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("协议层重启请求失败");
        assertThat(result.message()).contains("process not found");
        assertThat(result.processes()).isEmpty();
        assertThat(probe.urls).isEmpty();
    }

    @Test
    void restart_returnsFailureWhenProtocolLayerReturnsRestartFailure() {
        ProtocolRestartProperties properties = properties();
        FakeRestartClient restartClient = new FakeRestartClient(RemoteProtocolRestartResult.failure(
                "pm2 restart protocol-worker-1 --update-env",
                "protocol-master",
                List.of("protocol-worker-1"),
                false,
                "PM2 重启失败 exitCode=1 process not found"));
        FakeProbe probe = new FakeProbe(List.of());
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, restartClient, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("PM2 重启失败");
        assertThat(result.processes()).isEmpty();
    }

    @Test
    void restart_returnsFailureWhenAnyReadyUrlDoesNotBecomeReady() {
        ProtocolRestartProperties properties = properties();
        properties.setReadyTimeoutMs(0);
        FakeRestartClient restartClient = new FakeRestartClient(RemoteProtocolRestartResult.success(
                "pm2 restart protocol-worker-1 --update-env && pm2 restart protocol-master --update-env",
                "protocol-master",
                List.of("protocol-worker-1"),
                true,
                "protocol process restart scheduled"));
        FakeProbe probe = new FakeProbe(List.of(
                new ReadyProbeResult("http://127.0.0.1:8080/readyz", true, 200, null),
                new ReadyProbeResult("http://127.0.0.1:8081/readyz", false, 503, "not_ready")));
        ProtocolProcessRestartService service = new ProtocolProcessRestartServiceImpl(properties, restartClient, probe);

        ProtocolRestartVO result = service.restart();

        assertThat(result.success()).isFalse();
        assertThat(result.command()).isEqualTo("pm2 restart protocol-worker-1 --update-env && pm2 restart protocol-master --update-env");
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

    private static final class FakeRestartClient implements ProtocolProcessRestartClient {

        private final RemoteProtocolRestartResult result;
        private final RuntimeException exception;
        private int calls;

        private FakeRestartClient(RemoteProtocolRestartResult result) {
            this.result = result;
            this.exception = null;
        }

        private FakeRestartClient(RuntimeException exception) {
            this.result = null;
            this.exception = exception;
        }

        @Override
        public RemoteProtocolRestartResult restart() {
            calls++;
            if (exception != null) {
                throw exception;
            }
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
