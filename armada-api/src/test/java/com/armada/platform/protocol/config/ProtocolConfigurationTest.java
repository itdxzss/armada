package com.armada.platform.protocol.config;

import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.protocol.backend.android.AndroidGroupJoinErrorMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupJoinResponseMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupMembershipVerifier;
import com.armada.platform.protocol.backend.android.AndroidNativeClient;
import com.armada.platform.protocol.backend.android.AndroidResponseDecoder;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.http.ProtocolHttpExecutorRegistry;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupProfilePort;
import com.armada.platform.protocol.port.GroupPreviewPort;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.platform.protocol.routing.AccountRuntimeStatusBackend;
import com.armada.platform.protocol.routing.GroupJoinBackend;
import com.armada.platform.protocol.routing.MessageSendBackend;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
            .withBean(ProtocolCommandOutboxService.class,
                    () -> mock(ProtocolCommandOutboxService.class))
            .withBean(ProtocolMasterCommandProperties.class,
                    ProtocolMasterCommandProperties::new)
            .withBean(ProtocolAndroidCommandProperties.class,
                    ProtocolAndroidCommandProperties::new)
            .withUserConfiguration(ProtocolConfiguration.class);

    @Test
    void registersProtocolPropertiesFromConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ProtocolProperties.class);
            assertThat(context).hasSingleBean(RestClient.class);
            assertThat(context).hasSingleBean(ProtocolHttpExecutor.class);
            assertThat(context).hasSingleBean(ProtocolHttpExecutorRegistry.class);
            assertThat(context).hasSingleBean(AndroidNativeClient.class);
            assertThat(context).hasSingleBean(AndroidResponseDecoder.class);
            assertThat(context).hasSingleBean(AndroidGroupJoinErrorMapper.class);
            assertThat(context).hasSingleBean(AndroidGroupJoinResponseMapper.class);
            assertThat(context).hasSingleBean(AndroidGroupMembershipVerifier.class);
            assertThat(context).hasSingleBean(AccountLifecyclePort.class);
            assertThat(context).hasSingleBean(AccountRuntimeStatusPort.class);
            assertThat(context.getBeansOfType(AccountRuntimeStatusBackend.class))
                    .containsKeys(
                            "webAccountRuntimeStatusBackend",
                            "androidAccountRuntimeStatusBackend");
            assertThat(context).hasSingleBean(ContactPort.class);
            assertThat(context).hasSingleBean(GroupCreatePort.class);
            assertThat(context).hasSingleBean(GroupJoinPort.class);
            assertThat(context.getBeansOfType(GroupJoinBackend.class))
                    .containsKeys("webGroupJoinBackend", "androidGroupJoinBackend");
            assertThat(context).hasSingleBean(GroupParticipantPort.class);
            assertThat(context).hasSingleBean(GroupProfilePort.class);
            assertThat(context).hasSingleBean(GroupPreviewPort.class);
            assertThat(context).hasSingleBean(MessageSendPort.class);
            assertThat(context.getBeansOfType(MessageSendBackend.class))
                    .containsKeys("webMessageSendBackend", "androidMessageSendBackend");

            ProtocolProperties properties = context.getBean(ProtocolProperties.class);
            assertThat(properties.getBaseUrl()).isEqualTo("http://localhost:3000");
            assertThat(properties.getApiKey()).isEmpty();
            assertThat(properties.getConnectTimeoutMs()).isEqualTo(ProtocolProperties.DEFAULT_CONNECT_TIMEOUT_MS);
            assertThat(properties.getReadTimeoutMs()).isEqualTo(ProtocolProperties.DEFAULT_READ_TIMEOUT_MS);

            ProtocolHttpExecutorRegistry registry = context.getBean(ProtocolHttpExecutorRegistry.class);
            assertThat(registry.required(ProtocolBackend.WEB))
                    .isSameAs(context.getBean(ProtocolHttpExecutor.class));
            assertThat(registry.required(ProtocolBackend.ANDROID))
                    .isNotSameAs(context.getBean(ProtocolHttpExecutor.class));
        });
    }
}
