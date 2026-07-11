package com.armada.platform.protocol.config;

/**
 * 单个协议后端的 HTTP 连接配置。
 *
 * <p>{@code baseUrl} 与 {@code apiKey} 属敏感连接信息，禁止在日志或异常消息中明文输出。</p>
 */
public class ProtocolBackendHttpProperties {

    /**
     * 协议后端 HTTP base URL。
     */
    private String baseUrl;

    /**
     * 调用协议后端使用的 API key。
     */
    private String apiKey = "";

    /**
     * HTTP 连接超时时间，单位毫秒。
     */
    private int connectTimeoutMs = ProtocolProperties.DEFAULT_CONNECT_TIMEOUT_MS;

    /**
     * HTTP 读取超时时间，单位毫秒。
     */
    private int readTimeoutMs = ProtocolProperties.DEFAULT_READ_TIMEOUT_MS;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

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
        return "ProtocolBackendHttpProperties{"
                + "baseUrl=<redacted>"
                + ", apiKey=<redacted>"
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs
                + '}';
    }
}
