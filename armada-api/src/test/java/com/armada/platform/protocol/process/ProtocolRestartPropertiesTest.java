package com.armada.platform.protocol.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProtocolRestartPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsRestartPropertiesAndBuildsFixedCommand() {
        contextRunner
                .withPropertyValues(
                        "armada.protocol-restart.pm2-bin=/usr/bin/pm2",
                        "armada.protocol-restart.process-names[0]=protocol-master",
                        "armada.protocol-restart.process-names[1]=protocol-worker-1",
                        "armada.protocol-restart.ready-urls[0]=http://127.0.0.1:8080/readyz",
                        "armada.protocol-restart.ready-urls[1]=http://127.0.0.1:8081/readyz",
                        "armada.protocol-restart.command-timeout-ms=12345",
                        "armada.protocol-restart.ready-timeout-ms=23456",
                        "armada.protocol-restart.ready-poll-interval-ms=345",
                        "armada.protocol-restart.ready-request-timeout-ms=456")
                .run(context -> {
                    ProtocolRestartProperties properties = context.getBean(ProtocolRestartProperties.class);

                    assertThat(properties.getPm2Bin()).isEqualTo("/usr/bin/pm2");
                    assertThat(properties.getProcessNames()).containsExactly("protocol-master", "protocol-worker-1");
                    assertThat(properties.getReadyUrls()).containsExactly(
                            "http://127.0.0.1:8080/readyz",
                            "http://127.0.0.1:8081/readyz");
                    assertThat(properties.getCommandTimeoutMs()).isEqualTo(12345);
                    assertThat(properties.getReadyTimeoutMs()).isEqualTo(23456);
                    assertThat(properties.getReadyPollIntervalMs()).isEqualTo(345);
                    assertThat(properties.getReadyRequestTimeoutMs()).isEqualTo(456);
                    assertThat(properties.restartCommand()).containsExactly(
                            "/usr/bin/pm2",
                            "restart",
                            "protocol-master",
                            "protocol-worker-1",
                            "--update-env");
                });
    }

    @Test
    void providesDefaultProtocolMasterAndFourWorkers() {
        contextRunner.run(context -> {
            ProtocolRestartProperties properties = context.getBean(ProtocolRestartProperties.class);

            assertThat(properties.getPm2Bin()).isEqualTo("pm2");
            assertThat(properties.getProcessNames()).containsExactly(
                    "protocol-master",
                    "protocol-worker-1",
                    "protocol-worker-2",
                    "protocol-worker-3",
                    "protocol-worker-4");
            assertThat(properties.getReadyUrls()).containsExactly(
                    "http://127.0.0.1:8080/readyz",
                    "http://127.0.0.1:8081/readyz",
                    "http://127.0.0.1:8082/readyz",
                    "http://127.0.0.1:8083/readyz",
                    "http://127.0.0.1:8084/readyz");
            assertThat(properties.getCommandTimeoutMs()).isEqualTo(30_000);
            assertThat(properties.getReadyTimeoutMs()).isEqualTo(60_000);
            assertThat(properties.getReadyPollIntervalMs()).isEqualTo(1_000);
            assertThat(properties.getReadyRequestTimeoutMs()).isEqualTo(2_000);
        });
    }

    @EnableConfigurationProperties(ProtocolRestartProperties.class)
    static class TestConfig {
    }
}
