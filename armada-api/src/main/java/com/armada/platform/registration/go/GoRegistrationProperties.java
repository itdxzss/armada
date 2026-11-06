package com.armada.platform.registration.go;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 接码订单对接 Go 注册服务的连接参数；地址复用 Android 协议层地址，设备由 Go 自动生成，不需要逐租户配置。 */
@ConfigurationProperties(prefix = "armada.account.sms-registration")
public final class GoRegistrationProperties implements InitializingBean {

    /** 并发数上限，与 Go 的 max_concurrent 合法上限一致；Armada 并发超过 Go 额度只会得到 429。 */
    public static final int MAX_CONCURRENCY = 64;
    /** 超时配置上限；不是整个注册流程的总时限。 */
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    /** 一轮内同时办理的记录数，不得超过 Go 的 max_concurrent。 */
    private int concurrency = 32;
    /** TCP 连接及连接池等待上限。 */
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** 响应读取上限，必须大于 Go 单次外部操作的 20 秒期限。 */
    private Duration readTimeout = Duration.ofSeconds(25);

    /** 启动时校验并发数与超时。 */
    @Override
    public void afterPropertiesSet() {
        if (!validTimeout(connectTimeout) || !validTimeout(readTimeout)) {
            throw new IllegalStateException("Go 注册超时必须在 1 毫秒至 60 秒之间");
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalStateException("Go 注册并发数必须在 1 至 " + MAX_CONCURRENCY + " 之间");
        }
    }

    private static boolean validTimeout(Duration timeout) {
        return timeout != null && timeout.compareTo(Duration.ofMillis(1)) >= 0 && timeout.compareTo(MAX_TIMEOUT) <= 0;
    }

    /** @return 一轮并发数 */
    public int getConcurrency() { return concurrency; }
    /** @param concurrency 一轮并发数 */
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    /** @return 连接超时 */
    public Duration getConnectTimeout() { return connectTimeout; }
    /** @param connectTimeout 连接超时 */
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    /** @return 读取超时 */
    public Duration getReadTimeout() { return readTimeout; }
    /** @param readTimeout 读取超时 */
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
