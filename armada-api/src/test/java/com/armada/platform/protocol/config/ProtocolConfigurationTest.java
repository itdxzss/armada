package com.armada.platform.protocol.config;

import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.protocol.backend.android.AndroidGroupJoinErrorMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupJoinResponseMapper;
import com.armada.platform.protocol.backend.android.AndroidAccountParticipatingGroupMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupCreateResponseMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupMemberMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupMembershipVerifier;
import com.armada.platform.protocol.backend.android.AndroidGroupOperationErrorMapper;
import com.armada.platform.protocol.backend.android.AndroidNativeClient;
import com.armada.platform.protocol.backend.android.AndroidResponseDecoder;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.http.ProtocolHttpExecutorRegistry;
import com.armada.platform.protocol.idempotency.GroupCreateIdempotencyStore;
import com.armada.platform.protocol.media.AndroidImageAssetStore;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.port.AccountParticipatingGroupBatchPort;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.platform.protocol.port.GroupMetadataPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupProfilePort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.platform.protocol.port.GroupPreviewPort;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.platform.protocol.routing.AccountRuntimeStatusBackend;
import com.armada.platform.protocol.routing.AccountParticipatingGroupBackend;
import com.armada.platform.protocol.routing.ContactBackend;
import com.armada.platform.protocol.routing.GroupCreateBackend;
import com.armada.platform.protocol.routing.GroupJoinBackend;
import com.armada.platform.protocol.routing.GroupMemberListBackend;
import com.armada.platform.protocol.routing.GroupProfileBackend;
import com.armada.platform.protocol.routing.FixedAccountGroupMetadataBackend;
import com.armada.platform.protocol.routing.MessageSendBackend;
import com.armada.platform.protocol.routing.RoutingAccountParticipatingGroupPort;
import com.armada.platform.protocol.routing.RoutingFixedAccountGroupMetadataPort;
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
            .withBean(AndroidImageAssetStore.class,
                    () -> mock(AndroidImageAssetStore.class))
            .withBean(GroupCreateIdempotencyStore.class,
                    () -> mock(GroupCreateIdempotencyStore.class))
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
            assertThat(context).hasSingleBean(AndroidGroupMemberMapper.class);
            assertThat(context).hasSingleBean(AndroidAccountParticipatingGroupMapper.class);
            assertThat(context).hasSingleBean(AndroidGroupCreateResponseMapper.class);
            assertThat(context).hasSingleBean(AndroidGroupOperationErrorMapper.class);
            assertThat(context).hasSingleBean(AndroidGroupMembershipVerifier.class);
            assertThat(context).hasSingleBean(AccountLifecyclePort.class);
            assertThat(context).hasSingleBean(AccountRuntimeStatusPort.class);
            assertThat(context.getBeansOfType(AccountRuntimeStatusBackend.class))
                    .containsKeys(
                            "webAccountRuntimeStatusBackend",
                            "androidAccountRuntimeStatusBackend");
            assertThat(context).hasSingleBean(AccountParticipatingGroupPort.class);
            assertThat(context).hasSingleBean(AccountParticipatingGroupBatchPort.class);
            assertThat(context.getBean(AccountParticipatingGroupPort.class))
                    .isInstanceOf(RoutingAccountParticipatingGroupPort.class);
            assertThat(context.getBeansOfType(AccountParticipatingGroupBackend.class).values())
                    .extracting(AccountParticipatingGroupBackend::backend)
                    .containsExactlyInAnyOrder(ProtocolBackend.WEB, ProtocolBackend.ANDROID);
            assertThat(context).hasSingleBean(ContactPort.class);
            assertThat(context.getBeansOfType(ContactBackend.class))
                    .containsKeys("webContactBackend", "androidContactBackend");
            assertThat(context).hasSingleBean(GroupCreatePort.class);
            assertThat(context.getBeansOfType(GroupCreateBackend.class))
                    .containsKeys("webGroupCreateBackend", "androidGroupCreateBackend");
            assertThat(context).hasSingleBean(GroupJoinPort.class);
            assertThat(context.getBeansOfType(GroupJoinBackend.class))
                    .containsKeys("webGroupJoinBackend", "androidGroupJoinBackend");
            assertThat(context.getBean(GroupParticipantPort.class))
                    .isSameAs(context.getBean("groupParticipantPort"));
            assertThat(context.getBean(GroupInvitePort.class))
                    .isSameAs(context.getBean("groupInvitePort"));
            assertThat(context).hasSingleBean(GroupMemberListPort.class);
            assertThat(context.getBeansOfType(GroupMemberListBackend.class))
                    .containsKeys("webGroupMemberListBackend", "androidGroupMemberListBackend");
            assertThat(context).hasSingleBean(GroupMetadataPort.class);
            assertThat(context).hasSingleBean(FixedAccountGroupMetadataPort.class);
            assertThat(context.getBean(FixedAccountGroupMetadataPort.class))
                    .isInstanceOf(RoutingFixedAccountGroupMetadataPort.class);
            assertThat(context.getBeansOfType(FixedAccountGroupMetadataBackend.class).values())
                    .extracting(FixedAccountGroupMetadataBackend::backend)
                    .containsExactlyInAnyOrder(ProtocolBackend.WEB, ProtocolBackend.ANDROID);
            assertThat(context.getBean(GroupProfilePort.class))
                    .isSameAs(context.getBean("groupProfilePort"));
            assertThat(context.getBeansOfType(GroupProfileBackend.class))
                    .containsKeys("webGroupProfileBackend", "androidGroupProfileBackend");
            assertThat(context.getBean(GroupSettingsPort.class))
                    .isSameAs(context.getBean("groupSettingsPort"));
            assertThat(context.getBean(GroupLeavePort.class))
                    .isSameAs(context.getBean("groupLeavePort"));
            assertThat(context).hasSingleBean(GroupPreviewPort.class);
            assertThat(context).hasSingleBean(MessageSendPort.class);
            assertThat(context.getBeansOfType(MessageSendBackend.class))
                    .containsKeys("webMessageSendBackend", "androidMessageSendBackend");

            ProtocolAndroidCommandProperties androidProperties =
                    context.getBean(ProtocolAndroidCommandProperties.class);
            assertThat(androidProperties.getLifecycleTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_LIFECYCLE_TOPIC);
            assertThat(androidProperties.getMessageTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_MESSAGE_TOPIC);
            assertThat(androidProperties.getGroupJoinTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_GROUP_JOIN_TOPIC);
            assertThat(androidProperties.getGroupActionTopic())
                    .isEqualTo(ProtocolAndroidCommandProperties.DEFAULT_GROUP_ACTION_TOPIC);

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
