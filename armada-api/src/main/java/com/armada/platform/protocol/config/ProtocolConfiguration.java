package com.armada.platform.protocol.config;

import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.kafka.config.ProtocolMasterCommandProperties;
import com.armada.platform.protocol.backend.android.AndroidAccountRuntimeStatusAdapter;
import com.armada.platform.protocol.backend.android.AndroidAccountParticipatingGroupMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupCreateResponseMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupJoinErrorMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupJoinResponseMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupMemberMapper;
import com.armada.platform.protocol.backend.android.AndroidGroupMembershipVerifier;
import com.armada.platform.protocol.backend.android.AndroidGroupOperationErrorMapper;
import com.armada.platform.protocol.backend.android.AndroidNativeClient;
import com.armada.platform.protocol.backend.android.AndroidNativeAccountParticipatingGroupAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeContactAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeGroupCreateAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeGroupJoinAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeGroupParticipantAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeGroupProfileAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeGroupSettingsAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeGroupInviteAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeGroupLeaveAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeGroupMemberListAdapter;
import com.armada.platform.protocol.backend.android.AndroidNativeFixedAccountGroupMetadataAdapter;
import com.armada.platform.protocol.backend.android.AndroidResponseDecoder;
import com.armada.platform.protocol.backend.android.AndroidMessageSendBackend;
import com.armada.platform.protocol.backend.android.HttpAndroidNativeClient;
import com.armada.platform.protocol.backend.web.WebAccountRuntimeStatusAdapter;
import com.armada.platform.protocol.backend.web.WebMessageSendBackend;
import com.armada.platform.protocol.backend.web.WebNativeGroupJoinAdapter;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.http.ProtocolHttpExecutorRegistry;
import com.armada.platform.protocol.http.account.HttpAccountLifecycleAdapter;
import com.armada.platform.protocol.http.account.HttpAccountParticipatingGroupAdapter;
import com.armada.platform.protocol.http.contact.HttpContactAdapter;
import com.armada.platform.protocol.http.group.HttpGroupCreateAdapter;
import com.armada.platform.protocol.http.group.HttpGroupInviteAdapter;
import com.armada.platform.protocol.http.group.HttpGroupLeaveAdapter;
import com.armada.platform.protocol.http.group.HttpGroupMemberListAdapter;
import com.armada.platform.protocol.http.group.HttpGroupMetadataAdapter;
import com.armada.platform.protocol.http.group.HttpGroupParticipantAdapter;
import com.armada.platform.protocol.http.group.HttpGroupProfileAdapter;
import com.armada.platform.protocol.http.group.HttpGroupSettingsAdapter;
import com.armada.platform.protocol.http.group.HttpGroupPreviewAdapter;
import com.armada.platform.protocol.idempotency.GroupCreateIdempotencyStore;
import com.armada.platform.protocol.idempotency.IdempotentGroupCreatePort;
import com.armada.platform.protocol.media.AndroidImageAssetStore;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupJoinPort;
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
import com.armada.platform.protocol.routing.FixedAccountGroupMetadataBackend;
import com.armada.platform.protocol.routing.GroupCreateBackend;
import com.armada.platform.protocol.routing.GroupJoinBackend;
import com.armada.platform.protocol.routing.GroupParticipantBackend;
import com.armada.platform.protocol.routing.GroupProfileBackend;
import com.armada.platform.protocol.routing.GroupSettingsBackend;
import com.armada.platform.protocol.routing.GroupInviteBackend;
import com.armada.platform.protocol.routing.GroupLeaveBackend;
import com.armada.platform.protocol.routing.GroupMemberListBackend;
import com.armada.platform.protocol.routing.MessageSendBackend;
import com.armada.platform.protocol.routing.RoutingAccountRuntimeStatusPort;
import com.armada.platform.protocol.routing.RoutingAccountParticipatingGroupPort;
import com.armada.platform.protocol.routing.RoutingContactPort;
import com.armada.platform.protocol.routing.RoutingFixedAccountGroupMetadataPort;
import com.armada.platform.protocol.routing.RoutingGroupCreatePort;
import com.armada.platform.protocol.routing.RoutingGroupJoinPort;
import com.armada.platform.protocol.routing.RoutingGroupParticipantPort;
import com.armada.platform.protocol.routing.RoutingGroupProfilePort;
import com.armada.platform.protocol.routing.RoutingGroupSettingsPort;
import com.armada.platform.protocol.routing.RoutingGroupInvitePort;
import com.armada.platform.protocol.routing.RoutingGroupLeavePort;
import com.armada.platform.protocol.routing.RoutingGroupMemberListPort;
import com.armada.platform.protocol.routing.RoutingMessageSendPort;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.trace.TraceIdClientHttpRequestInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.EnumMap;
import java.util.List;

/**
 * 协议层防腐层 Spring 配置。
 *
 * <p>当前注册协议层基础配置、共享 {@link RestClient}、{@link ProtocolHttpExecutor}
 * 与账号、群组、联系人等现有协议能力 adapter；各能力按独立端口增量装配。</p>
 */
@Configuration
@EnableConfigurationProperties(ProtocolProperties.class)
public class ProtocolConfiguration {

    /**
     * 注册协议层共享 RestClient。
     *
     * @param properties 协议层连接配置
     * @return 配好 baseUrl、超时、JSON 头和可选 API key 的 RestClient
     */
    @Bean
    public RestClient protocolRestClient(ProtocolProperties properties) {
        return buildRestClient(properties.requireBackend(ProtocolBackend.WEB));
    }

    /**
     * 根据单个协议后端配置创建 RestClient。
     *
     * @param properties 协议后端 HTTP 配置
     * @return 配好 baseUrl、超时、JSON 头和可选 API key 的 RestClient
     */
    private static RestClient buildRestClient(ProtocolBackendHttpProperties properties) {
        // 使用 Spring 自带的简单请求工厂,便于在这里直接设置连接和读取超时。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 设置 TCP 连接建立超时,避免协议层不可达时长时间卡住业务线程。
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        // 设置响应读取超时,避免协议层已连接但迟迟不返回时无限等待。
        factory.setReadTimeout(properties.getReadTimeoutMs());

        // 创建 RestClient 构造器,后续统一写入协议层 baseUrl、请求工厂和默认 JSON 头。
        RestClient.Builder builder = RestClient.builder()
                // 设置协议层基础地址,adapter 后续只需要传相对路径。
                .baseUrl(properties.getBaseUrl())
                // 挂载带超时配置的请求工厂。
                .requestFactory(factory)
                // 透传当前请求或任务的追踪标识，便于跨服务关联日志。
                .requestInterceptor(new TraceIdClientHttpRequestInterceptor())
                // 声明客户端期望协议层返回 JSON。
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                // 声明默认请求体按 JSON 发送。
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        // apiKey 非空时才写请求头,本地开发允许不配置鉴权。
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            // 写入协议层约定的 x-api-key 请求头。
            builder.defaultHeader(ProtocolHttpExecutor.API_KEY_HEADER, properties.getApiKey());
        }
        // 构建不可变 RestClient Bean,交给 ProtocolHttpExecutor 复用。
        return builder.build();
    }

    /**
     * 注册协议层 HTTP 执行器。
     *
     * @param protocolRestClient 协议层共享 RestClient
     * @return 协议层 HTTP 执行器
     */
    @Bean
    public ProtocolHttpExecutor protocolHttpExecutor(RestClient protocolRestClient) {
        return new ProtocolHttpExecutor(protocolRestClient);
    }

    /**
     * 注册各协议后端的 HTTP 执行器。
     *
     * <p>Web 复用现有执行器 Bean，保持存量 adapter 的注入方式不变；Android 使用独立的
     * RestClient 和连接配置。</p>
     *
     * @param properties 协议层连接配置
     * @param protocolHttpExecutor 现有 Web HTTP 执行器
     * @return 协议后端 HTTP 执行器注册表
     */
    @Bean
    public ProtocolHttpExecutorRegistry protocolHttpExecutorRegistry(
            ProtocolProperties properties,
            ProtocolHttpExecutor protocolHttpExecutor) {
        // 使用 EnumMap 明确限定 key 为 ProtocolBackend，避免用字符串维护后端名称。
        EnumMap<ProtocolBackend, ProtocolHttpExecutor> executors =
                new EnumMap<>(ProtocolBackend.class);

        // Web 继续复用现有 ProtocolHttpExecutor Bean，使存量 Web adapter 的连接配置和注入关系保持不变。
        executors.put(ProtocolBackend.WEB, protocolHttpExecutor);

        // Android 从自己的 backend 配置创建独立 RestClient，确保请求地址、API key 和超时不与 Web 串用。
        executors.put(ProtocolBackend.ANDROID, new ProtocolHttpExecutor(
                buildRestClient(properties.requireBackend(ProtocolBackend.ANDROID))));

        // 注册表只负责按后端提供对应 executor；具体账号走哪个后端由上层业务路由决定。
        return new ProtocolHttpExecutorRegistry(executors);
    }

    /**
     * 注册复用 Android 专属 HTTP executor 的原生 client。
     *
     * @param registry 按协议后端保存的 HTTP 执行器注册表
     * @return Android Zhuan 原生 client
     */
    @Bean
    public AndroidNativeClient androidNativeClient(ProtocolHttpExecutorRegistry registry) {
        return new HttpAndroidNativeClient(registry.required(ProtocolBackend.ANDROID));
    }

    /**
     * 注册 Android 原生响应 decoder。
     *
     * @return Android 原生响应 decoder
     */
    @Bean
    public AndroidResponseDecoder androidResponseDecoder() {
        return new AndroidResponseDecoder();
    }

    /**
     * 注册 Android 原生业务错误 mapper。
     *
     * @return Android 原生业务错误 mapper
     */
    @Bean
    public AndroidGroupJoinErrorMapper androidGroupJoinErrorMapper() {
        return new AndroidGroupJoinErrorMapper();
    }

    /**
     * 注册 Android 邀请码与进群成功响应 mapper。
     *
     * @return Android 进群响应 mapper
     */
    @Bean
    public AndroidGroupJoinResponseMapper androidGroupJoinResponseMapper() {
        return new AndroidGroupJoinResponseMapper();
    }

    /**
     * 注册 Android 群成员响应 mapper。
     *
     * @return Android 群成员响应 mapper
     */
    @Bean
    public AndroidGroupMemberMapper androidGroupMemberMapper() {
        return new AndroidGroupMemberMapper();
    }

    /**
     * 注册 Android 当前参与群响应 mapper。
     *
     * @param memberMapper Android 群成员响应 mapper
     * @return Android 当前参与群响应 mapper
     */
    @Bean
    public AndroidAccountParticipatingGroupMapper androidAccountParticipatingGroupMapper(
            AndroidGroupMemberMapper memberMapper) {
        return new AndroidAccountParticipatingGroupMapper(memberMapper);
    }

    /**
     * 注册 Android 建群成功响应 mapper。
     *
     * @param memberMapper Android 群成员响应 mapper
     * @return Android 建群成功响应 mapper
     */
    @Bean
    public AndroidGroupCreateResponseMapper androidGroupCreateResponseMapper(
            AndroidGroupMemberMapper memberMapper) {
        return new AndroidGroupCreateResponseMapper(memberMapper);
    }

    /**
     * 注册 Android 联系人和群操作错误 mapper。
     *
     * @return Android 联系人和群操作错误 mapper
     */
    @Bean
    public AndroidGroupOperationErrorMapper androidGroupOperationErrorMapper() {
        return new AndroidGroupOperationErrorMapper();
    }

    /**
     * 注册 Android 群成员二次确认器。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param memberMapper Android 群成员响应 mapper
     * @return Android 群成员确认器
     */
    @Bean
    public AndroidGroupMembershipVerifier androidGroupMembershipVerifier(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupMemberMapper memberMapper) {
        return new AndroidGroupMembershipVerifier(client, decoder, memberMapper);
    }

    /**
     * 注册账号生命周期协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 账号生命周期端口 HTTP 实现
     */
    @Bean
    public AccountLifecyclePort accountLifecyclePort(ProtocolHttpExecutor protocolHttpExecutor) {
        return new HttpAccountLifecycleAdapter(protocolHttpExecutor);
    }

    /**
     * 注册 Web/Baileys 账号运行态 backend。
     *
     * @param registry 按协议后端保存的 HTTP 执行器注册表
     * @return Web 账号运行态 backend
     */
    @Bean
    public AccountRuntimeStatusBackend webAccountRuntimeStatusBackend(
            ProtocolHttpExecutorRegistry registry) {
        return new WebAccountRuntimeStatusAdapter(registry.required(ProtocolBackend.WEB));
    }

    /**
     * 注册 Android Zhuan 账号运行态 backend。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 原生业务错误 mapper
     * @return Android 账号运行态 backend
     */
    @Bean
    public AccountRuntimeStatusBackend androidAccountRuntimeStatusBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupJoinErrorMapper errorMapper) {
        return new AndroidAccountRuntimeStatusAdapter(client, decoder, errorMapper);
    }

    /**
     * 注册统一账号运行态查询端口，由路由实现按账号协议后端选择具体 backend。
     *
     * @param backends Spring 收集的所有账号运行态 backend
     * @return 后端感知的统一账号运行态查询端口
     */
    @Bean
    public AccountRuntimeStatusPort accountRuntimeStatusPort(
            List<AccountRuntimeStatusBackend> backends) {
        return new RoutingAccountRuntimeStatusPort(backends);
    }

    /**
     * 注册 Web/Baileys 固定账号参与群读取和批量查群后端。
     *
     * @param registry 按协议后端保存的 HTTP 执行器注册表
     * @return Web/Baileys 参与群 HTTP adapter
     */
    @Bean
    public HttpAccountParticipatingGroupAdapter webAccountParticipatingGroupBackend(
            ProtocolHttpExecutorRegistry registry) {
        return new HttpAccountParticipatingGroupAdapter(
                registry.required(ProtocolBackend.WEB));
    }

    /**
     * 注册 Android Zhuan 固定账号参与群读取后端。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @param mapper Android 当前群响应 mapper
     * @return Android Zhuan 固定账号参与群读取后端
     */
    @Bean
    public AccountParticipatingGroupBackend androidAccountParticipatingGroupBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper,
            AndroidAccountParticipatingGroupMapper mapper) {
        return new AndroidNativeAccountParticipatingGroupAdapter(
                client,
                decoder,
                errorMapper,
                mapper);
    }

    /**
     * 注册统一账号当前参与群查询端口。
     *
     * @param backends Spring 收集的所有固定账号参与群读取后端
     * @return 后端感知的统一参与群查询端口
     */
    @Bean
    public AccountParticipatingGroupPort accountParticipatingGroupPort(
            List<AccountParticipatingGroupBackend> backends) {
        return new RoutingAccountParticipatingGroupPort(backends);
    }

    /** 注册 Web/Baileys 原生进群 backend。 */
    @Bean
    public GroupJoinBackend webGroupJoinBackend(ProtocolHttpExecutorRegistry registry) {
        return new WebNativeGroupJoinAdapter(registry.required(ProtocolBackend.WEB));
    }

    /**
     * 注册 Android Zhuan 原生进群 backend。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 原生业务错误 mapper
     * @param responseMapper Android 邀请与成功响应 mapper
     * @param verifier Android 群成员确认器
     * @return Android 原生进群 backend
     */
    @Bean
    public GroupJoinBackend androidGroupJoinBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupJoinErrorMapper errorMapper,
            AndroidGroupJoinResponseMapper responseMapper,
            AndroidGroupMembershipVerifier verifier) {
        return new AndroidNativeGroupJoinAdapter(
                client,
                decoder,
                errorMapper,
                responseMapper,
                verifier);
    }

    /**
     * 注册统一进群端口，由路由实现根据账号协议后端选择具体 backend。
     *
     * @param backends Spring 收集的所有进群 backend
     * @return 后端感知的统一进群端口
     */
    @Bean
    public GroupJoinPort groupJoinPort(List<GroupJoinBackend> backends) {
        return new RoutingGroupJoinPort(backends);
    }

    /**
     * 注册 Web/Baileys 营销消息 backend。
     *
     * @param outboxService 协议命令 outbox 服务
     * @param properties Web master 命令 topic 配置
     * @return Web 营销消息 backend
     */
    @Bean
    public MessageSendBackend webMessageSendBackend(
            ProtocolCommandOutboxService outboxService,
            ProtocolMasterCommandProperties properties) {
        return new WebMessageSendBackend(outboxService, properties);
    }

    /**
     * 注册 Android Zhuan 营销消息 backend。
     *
     * @param outboxService 协议命令 outbox 服务
     * @param properties Android 营销消息命令 topic 配置
     * @param assetStore Android 营销图片共享 Redis 缓存
     * @return Android 营销消息 backend
     */
    @Bean
    public MessageSendBackend androidMessageSendBackend(
            ProtocolCommandOutboxService outboxService,
            ProtocolAndroidCommandProperties properties,
            AndroidImageAssetStore assetStore) {
        return new AndroidMessageSendBackend(outboxService, properties, assetStore);
    }

    /**
     * 注册统一消息发送端口，由路由实现根据账号协议后端选择具体 backend。
     *
     * @param backends Spring 收集的所有消息发送 backend
     * @return 后端感知的统一消息发送端口
     */
    @Bean
    public MessageSendPort messageSendPort(List<MessageSendBackend> backends) {
        return new RoutingMessageSendPort(backends);
    }

    /**
     * 注册 Web/Baileys 建群后端。
     *
     * @param registry 按协议后端保存的 HTTP 执行器注册表
     * @return Web/Baileys 建群后端
     */
    @Bean
    public GroupCreateBackend webGroupCreateBackend(ProtocolHttpExecutorRegistry registry) {
        return new HttpGroupCreateAdapter(registry.required(ProtocolBackend.WEB));
    }

    /**
     * 注册 Android Zhuan 原生建群后端。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @param responseMapper Android 建群成功响应 mapper
     * @return Android Zhuan 建群后端
     */
    @Bean
    public GroupCreateBackend androidGroupCreateBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper,
            AndroidGroupCreateResponseMapper responseMapper) {
        return new AndroidNativeGroupCreateAdapter(
                client, decoder, errorMapper, responseMapper);
    }

    /**
     * 注册统一建群端口，由路由实现根据账号协议后端选择具体 backend。
     *
     * @param backends Spring 收集的所有建群 backend
     * @param store 建群严格幂等存储
     * @return 后端感知且严格幂等的统一建群端口
     */
    @Bean
    public GroupCreatePort groupCreatePort(
            List<GroupCreateBackend> backends,
            GroupCreateIdempotencyStore store) {
        return new IdempotentGroupCreatePort(new RoutingGroupCreatePort(backends), store);
    }

    /**
     * 注册 Web/Baileys 联系人保存后端。
     *
     * @param registry 按协议后端保存的 HTTP 执行器注册表
     * @return Web/Baileys 联系人保存后端
     */
    @Bean
    public ContactBackend webContactBackend(ProtocolHttpExecutorRegistry registry) {
        return new HttpContactAdapter(registry.required(ProtocolBackend.WEB));
    }

    /**
     * 注册 Android Zhuan 原生联系人保存后端。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @return Android Zhuan 联系人保存后端
     */
    @Bean
    public ContactBackend androidContactBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper) {
        return new AndroidNativeContactAdapter(client, decoder, errorMapper);
    }

    /**
     * 注册统一联系人保存端口，由路由实现根据账号协议后端选择具体 backend。
     *
     * @param backends Spring 收集的所有联系人保存 backend
     * @return 后端感知的统一联系人保存端口
     */
    @Bean
    public ContactPort contactPort(List<ContactBackend> backends) {
        return new RoutingContactPort(backends);
    }

    /**
     * 注册 Web/Baileys 群成员列表查询后端。
     *
     * @param registry 按协议后端保存的 HTTP 执行器注册表
     * @return Web/Baileys 群成员列表查询后端
     */
    @Bean
    public GroupMemberListBackend webGroupMemberListBackend(
            ProtocolHttpExecutorRegistry registry) {
        return new HttpGroupMemberListAdapter(registry.required(ProtocolBackend.WEB));
    }

    /**
     * 注册 Android Zhuan 原生群成员列表查询后端。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @param memberMapper Android 群成员响应 mapper
     * @return Android Zhuan 群成员列表查询后端
     */
    @Bean
    public GroupMemberListBackend androidGroupMemberListBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper,
            AndroidGroupMemberMapper memberMapper) {
        return new AndroidNativeGroupMemberListAdapter(
                client, decoder, errorMapper, memberMapper);
    }

    /**
     * 注册统一群成员列表查询端口，由路由实现根据账号协议后端选择具体 backend。
     *
     * @param backends Spring 收集的所有群成员查询 backend
     * @return 后端感知的统一群成员列表查询端口
     */
    @Bean
    public GroupMemberListPort groupMemberListPort(List<GroupMemberListBackend> backends) {
        return new RoutingGroupMemberListPort(backends);
    }

    /**
     * 注册群成员实时查询协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 群成员查询端口 HTTP 实现
     */
    @Bean
    public GroupParticipantBackend webGroupParticipantBackend(ProtocolHttpExecutorRegistry registry) {
        return new HttpGroupParticipantAdapter(registry.required(ProtocolBackend.WEB));
    }

    @Bean
    public GroupParticipantBackend androidGroupParticipantBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper) {
        return new AndroidNativeGroupParticipantAdapter(client, decoder, errorMapper);
    }

    @Bean
    @Primary
    public GroupParticipantPort groupParticipantPort(List<GroupParticipantBackend> backends) {
        return new RoutingGroupParticipantPort(backends);
    }

    /**
     * 注册群邀请链接查询协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 群邀请链接查询端口 HTTP 实现
     */
    @Bean
    public GroupInviteBackend webGroupInviteBackend(ProtocolHttpExecutorRegistry registry) {
        return new HttpGroupInviteAdapter(registry.required(ProtocolBackend.WEB));
    }

    @Bean
    public GroupInviteBackend androidGroupInviteBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper) {
        return new AndroidNativeGroupInviteAdapter(client, decoder, errorMapper);
    }

    @Bean
    @Primary
    public GroupInvitePort groupInvitePort(List<GroupInviteBackend> backends) {
        return new RoutingGroupInvitePort(backends);
    }

    /**
     * 注册 Web/Baileys 群详情读取和写前校验 adapter。
     *
     * @param registry 按协议后端保存的 HTTP 执行器注册表
     * @return Web/Baileys 群详情 adapter
     */
    @Bean
    public HttpGroupMetadataAdapter webGroupMetadataAdapter(
            ProtocolHttpExecutorRegistry registry) {
        return new HttpGroupMetadataAdapter(
                registry.required(ProtocolBackend.WEB));
    }

    /**
     * 注册 Android Zhuan 固定账号只读群 metadata 后端。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     * @param memberMapper Android 群成员响应 mapper
     * @return Android Zhuan 固定账号只读群 metadata 后端
     */
    @Bean
    public FixedAccountGroupMetadataBackend androidFixedAccountGroupMetadataBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper,
            AndroidGroupMemberMapper memberMapper) {
        return new AndroidNativeFixedAccountGroupMetadataAdapter(
                client,
                decoder,
                errorMapper,
                memberMapper);
    }

    /**
     * 注册统一固定账号只读群 metadata 端口。
     *
     * @param backends Spring 收集的所有固定账号只读群 metadata 后端
     * @return 后端感知的统一只读群 metadata 端口
     */
    @Bean
    public FixedAccountGroupMetadataPort fixedAccountGroupMetadataPort(
            List<FixedAccountGroupMetadataBackend> backends) {
        return new RoutingFixedAccountGroupMetadataPort(backends);
    }

    /**
     * 注册群资料修改协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 群资料修改端口 HTTP 实现
     */
    @Bean
    public GroupProfileBackend webGroupProfileBackend(ProtocolHttpExecutorRegistry registry) {
        return new HttpGroupProfileAdapter(registry.required(ProtocolBackend.WEB));
    }

    @Bean
    public GroupProfileBackend androidGroupProfileBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper) {
        return new AndroidNativeGroupProfileAdapter(client, decoder, errorMapper);
    }

    @Bean
    @Primary
    public GroupProfilePort groupProfilePort(List<GroupProfileBackend> backends) {
        return new RoutingGroupProfilePort(backends);
    }

    /**
     * 注册群设置协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 群设置 HTTP 实现
     */
    @Bean
    public GroupSettingsBackend webGroupSettingsBackend(ProtocolHttpExecutorRegistry registry) {
        return new HttpGroupSettingsAdapter(registry.required(ProtocolBackend.WEB));
    }

    @Bean
    public GroupSettingsBackend androidGroupSettingsBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper) {
        return new AndroidNativeGroupSettingsAdapter(client, decoder, errorMapper);
    }

    @Bean
    @Primary
    public GroupSettingsPort groupSettingsPort(List<GroupSettingsBackend> backends) {
        return new RoutingGroupSettingsPort(backends);
    }

    @Bean
    public GroupLeaveBackend webGroupLeaveBackend(ProtocolHttpExecutorRegistry registry) {
        return new HttpGroupLeaveAdapter(registry.required(ProtocolBackend.WEB));
    }

    @Bean
    public GroupLeaveBackend androidGroupLeaveBackend(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errorMapper) {
        return new AndroidNativeGroupLeaveAdapter(client, decoder, errorMapper);
    }

    @Bean
    @Primary
    public GroupLeavePort groupLeavePort(List<GroupLeaveBackend> backends) {
        return new RoutingGroupLeavePort(backends);
    }

    /**
     * 注册群预览协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 群预览端口 HTTP 实现
     */
    @Bean
    public GroupPreviewPort groupPreviewPort(ProtocolHttpExecutor protocolHttpExecutor) {
        return new HttpGroupPreviewAdapter(protocolHttpExecutor);
    }
}
