package com.armada.platform.protocol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议层连接配置。
 *
 * <p>本类只承载 armada 调用协议层所需的基础连接属性,不创建 HTTP 客户端、不注册全局配置。
 * {@code apiKey} 属敏感信息,禁止在日志或异常消息中明文输出。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol")
public class ProtocolProperties {

    /**
     * 默认连接超时时间,单位毫秒。
     */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;

    /**
     * 默认读取超时时间,单位毫秒。
     */
    public static final int DEFAULT_READ_TIMEOUT_MS = 60_000;

    /**
     * 协议层 HTTP base URL。
     */
    private String baseUrl;

    /**
     * 调用协议层使用的 API key。
     */
    private String apiKey;

    /**
     * HTTP 连接超时时间,单位毫秒。
     */
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;

    /**
     * HTTP 读取超时时间,单位毫秒。
     */
    private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

    /**
     * 获取协议层 HTTP base URL。
     *
     * @return 协议层 HTTP base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 设置协议层 HTTP base URL。
     *
     * @param baseUrl 协议层 HTTP base URL
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 获取协议层 API key。
     *
     * @return 协议层 API key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 设置协议层 API key。
     *
     * @param apiKey 协议层 API key
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 获取 HTTP 连接超时时间。
     *
     * @return HTTP 连接超时时间,单位毫秒
     */
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * 设置 HTTP 连接超时时间。
     *
     * @param connectTimeoutMs HTTP 连接超时时间,单位毫秒
     */
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * 获取 HTTP 读取超时时间。
     *
     * @return HTTP 读取超时时间,单位毫秒
     */
    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * 设置 HTTP 读取超时时间。
     *
     * @param readTimeoutMs HTTP 读取超时时间,单位毫秒
     */
    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * 返回脱敏后的配置摘要。
     *
     * @return 不包含 baseUrl/apiKey 明文的配置摘要
     */
    @Override
    public String toString() {
        return "ProtocolProperties{"
                + "baseUrl=<redacted>"
                + ", apiKey=<redacted>"
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs
                + '}';
    }
}
