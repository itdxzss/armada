package com.armada.platform.sms.grizzly;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Grizzly 接码配置；只要配置了 API 密钥就视为启用，没有任何额外开关。 */
@ConfigurationProperties(prefix = "armada.sms.grizzly")
public final class GrizzlySmsProperties implements InitializingBean {

    /** 连接及读取等待的配置上限；不是完整请求或注册任务的总时限。 */
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    /** 仅由服务端环境注入，不进入业务 DTO 或日志。 */
    private String apiKey = "";
    /** TCP 连接及连接池等待上限。 */
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** HTTP 响应及 socket 读取上限。 */
    private Duration readTimeout = Duration.ofSeconds(20);

    /** 启动时校验密钥格式和超时，错误信息不包含密钥值；未配置密钥时允许启动。 */
    @Override
    public void afterPropertiesSet() {
        if (apiKey != null && !apiKey.isEmpty() && (apiKey.isBlank()
                || apiKey.chars().anyMatch(character -> Character.isWhitespace(character)
                        || Character.isISOControl(character)))) {
            throw new IllegalStateException("Grizzly API 密钥格式无效");
        }
        if (!validTimeout(connectTimeout) || !validTimeout(readTimeout)) {
            throw new IllegalStateException("Grizzly 接码超时必须在 1 毫秒至 60 秒之间");
        }
    }

    private boolean validTimeout(Duration timeout) {
        return timeout != null && timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(MAX_TIMEOUT) <= 0;
    }

    /** @return 是否已配置 API 密钥；已配置即允许查询和取号 */
    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    /** @return 服务端密钥，仅供 HTTP 客户端构建请求 */
    public String getApiKey() { return apiKey; }
    /** @param apiKey 环境注入的供应商密钥 */
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    /** @return 连接及连接池等待上限 */
    public Duration getConnectTimeout() { return connectTimeout; }
    /** @param connectTimeout 连接及连接池等待上限 */
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    /** @return 响应及 socket 读取上限 */
    public Duration getReadTimeout() { return readTimeout; }
    /** @param readTimeout 响应及 socket 读取上限 */
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
