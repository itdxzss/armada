package com.armada.platform.sms.grizzly;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Grizzly 接码配置；查询与会改变供应商订单的操作分别启用。 */
@ConfigurationProperties(prefix = "armada.sms.grizzly")
public final class GrizzlySmsProperties implements InitializingBean {

    /** 连接及读取等待的配置上限；不是完整请求或注册任务的总时限。 */
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    /** 默认关闭，应用启动不会访问供应商。 */
    private boolean enabled;
    /** 取号及状态修改开关；仅配置密钥不会产生订单。 */
    private boolean purchasesEnabled;
    /** 仅由服务端环境注入，不进入业务 DTO 或日志。 */
    private String apiKey = "";
    /** TCP 连接及连接池等待上限。 */
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** HTTP 响应及 socket 读取上限。 */
    private Duration readTimeout = Duration.ofSeconds(20);

    /** 启动时校验启用状态和超时，错误信息不包含密钥值。 */
    @Override
    public void afterPropertiesSet() {
        if (enabled && (apiKey == null || apiKey.isBlank()
                || apiKey.chars().anyMatch(character -> Character.isWhitespace(character)
                        || Character.isISOControl(character)))) {
            throw new IllegalStateException("Grizzly 接码启用时必须配置有效的 API 密钥");
        }
        if (purchasesEnabled && !enabled) {
            throw new IllegalStateException("Grizzly 订单操作需要先启用接码服务");
        }
        if (!validTimeout(connectTimeout) || !validTimeout(readTimeout)) {
            throw new IllegalStateException("Grizzly 接码超时必须在 1 毫秒至 60 秒之间");
        }
    }

    private boolean validTimeout(Duration timeout) {
        return timeout != null && timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(MAX_TIMEOUT) <= 0;
    }

    /** @return 是否启用供应商查询 */
    public boolean isEnabled() { return enabled; }
    /** @param enabled 是否启用供应商查询 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** @return 是否允许取号和修改订单状态 */
    public boolean isPurchasesEnabled() { return purchasesEnabled; }
    /** @param purchasesEnabled 是否允许取号和修改订单状态 */
    public void setPurchasesEnabled(boolean purchasesEnabled) { this.purchasesEnabled = purchasesEnabled; }
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
