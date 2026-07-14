package com.armada.platform.protocol.config;

import com.armada.platform.protocol.backend.android.AndroidAccountRuntimeStatusAdapter;
import com.armada.platform.protocol.backend.android.AndroidGroupJoinErrorMapper;
import com.armada.platform.protocol.backend.android.AndroidNativeClient;
import com.armada.platform.protocol.backend.android.AndroidResponseDecoder;
import com.armada.platform.protocol.backend.android.HttpAndroidNativeClient;
import com.armada.platform.protocol.backend.web.WebAccountRuntimeStatusAdapter;
import com.armada.platform.protocol.backend.web.WebNativeGroupJoinAdapter;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.http.ProtocolHttpExecutorRegistry;
import com.armada.platform.protocol.http.account.HttpAccountLifecycleAdapter;
import com.armada.platform.protocol.http.account.HttpAccountParticipatingGroupAdapter;
import com.armada.platform.protocol.http.contact.HttpContactAdapter;
import com.armada.platform.protocol.http.group.HttpGroupCreateAdapter;
import com.armada.platform.protocol.http.group.HttpGroupParticipantAdapter;
import com.armada.platform.protocol.http.group.HttpGroupProfileAdapter;
import com.armada.platform.protocol.http.group.HttpGroupPreviewAdapter;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupProfilePort;
import com.armada.platform.protocol.port.GroupPreviewPort;
import com.armada.platform.protocol.routing.AccountRuntimeStatusBackend;
import com.armada.platform.protocol.routing.GroupJoinBackend;
import com.armada.platform.protocol.routing.RoutingAccountRuntimeStatusPort;
import com.armada.platform.protocol.routing.RoutingGroupJoinPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
     * 注册账号当前参与群查询协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 账号参与群查询端口 HTTP 实现
     */
    @Bean
    public AccountParticipatingGroupPort accountParticipatingGroupPort(ProtocolHttpExecutor protocolHttpExecutor) {
        return new HttpAccountParticipatingGroupAdapter(protocolHttpExecutor);
    }

    /** 注册 Web/Baileys 原生进群 backend。 */
    @Bean
    public GroupJoinBackend webGroupJoinBackend(ProtocolHttpExecutorRegistry registry) {
        return new WebNativeGroupJoinAdapter(registry.required(ProtocolBackend.WEB));
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
     * 注册建群协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 建群端口 HTTP 实现
     */
    @Bean
    public GroupCreatePort groupCreatePort(ProtocolHttpExecutor protocolHttpExecutor) {
        return new HttpGroupCreateAdapter(protocolHttpExecutor);
    }

    /**
     * 注册联系人保存协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 联系人保存端口 HTTP 实现
     */
    @Bean
    public ContactPort contactPort(ProtocolHttpExecutor protocolHttpExecutor) {
        return new HttpContactAdapter(protocolHttpExecutor);
    }

    /**
     * 注册群成员实时查询协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 群成员查询端口 HTTP 实现
     */
    @Bean
    public GroupParticipantPort groupParticipantPort(ProtocolHttpExecutor protocolHttpExecutor) {
        return new HttpGroupParticipantAdapter(protocolHttpExecutor);
    }

    /**
     * 注册群资料修改协议端口。
     *
     * @param protocolHttpExecutor 协议层 HTTP 执行器
     * @return 群资料修改端口 HTTP 实现
     */
    @Bean
    public GroupProfilePort groupProfilePort(ProtocolHttpExecutor protocolHttpExecutor) {
        return new HttpGroupProfileAdapter(protocolHttpExecutor);
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
