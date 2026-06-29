package com.armada.platform.protocol.config;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupPreviewPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader()
                            .load("application", new FileSystemResource("src/main/resources/application.yml"))
                            .forEach(propertySource -> context.getEnvironment()
                                    .getPropertySources()
                                    .addLast(propertySource));
                } catch (IOException e) {
                    throw new IllegalStateException("读取 application.yml 失败", e);
                }
            })
            .withUserConfiguration(ProtocolConfiguration.class);

    @Test
    void registersProtocolPropertiesFromConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ProtocolProperties.class);
            assertThat(context).hasSingleBean(RestClient.class);
            assertThat(context).hasSingleBean(ProtocolHttpExecutor.class);
            assertThat(context).hasSingleBean(AccountLifecyclePort.class);
            assertThat(context).hasSingleBean(GroupJoinPort.class);
            assertThat(context).hasSingleBean(GroupPreviewPort.class);

            ProtocolProperties properties = context.getBean(ProtocolProperties.class);
            assertThat(properties.getBaseUrl()).isEqualTo("http://localhost:3000");
            assertThat(properties.getApiKey()).isEmpty();
            assertThat(properties.getConnectTimeoutMs()).isEqualTo(ProtocolProperties.DEFAULT_CONNECT_TIMEOUT_MS);
            assertThat(properties.getReadTimeoutMs()).isEqualTo(ProtocolProperties.DEFAULT_READ_TIMEOUT_MS);
        });
    }
}
