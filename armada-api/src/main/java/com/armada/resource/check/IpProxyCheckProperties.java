package com.armada.resource.check;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IP 代理检测线程池和网络超时配置。
 */
@ConfigurationProperties(prefix = "armada.ip-proxy-check")
public class IpProxyCheckProperties {

    private final ExecutorProperties executor = new ExecutorProperties();
    private final TimeoutProperties timeout = new TimeoutProperties();

    public ExecutorProperties getExecutor() {
        return executor;
    }

    public TimeoutProperties getTimeout() {
        return timeout;
    }

    /**
     * 导入后异步全量检测线程池参数。
     */
    public static class ExecutorProperties {
        private int coreSize = 4;
        private int maxSize = 12;
        private int queueCapacity = 5_000;

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    /**
     * 单次代理检测的网络超时参数,单位毫秒。
     */
    public static class TimeoutProperties {
        private int connectMs = 5_000;
        private int readMs = 8_000;
        private int totalMs = 15_000;

        public int getConnectMs() {
            return connectMs;
        }

        public void setConnectMs(int connectMs) {
            this.connectMs = connectMs;
        }

        public int getReadMs() {
            return readMs;
        }

        public void setReadMs(int readMs) {
            this.readMs = readMs;
        }

        public int getTotalMs() {
            return totalMs;
        }

        public void setTotalMs(int totalMs) {
            this.totalMs = totalMs;
        }
    }
}
