package com.armada.platform.protocol.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Android 营销图片共享 Redis 连接配置。
 *
 * <p>该连接只保存 Android 营销图片原始二进制，物理 Key 前缀必须与 Android 进程配置一致。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.android-image-cache.redis")
public final class AndroidImageRedisProperties implements InitializingBean {

    /** 单机 Redis 模式。 */
    private static final String STANDALONE_MODE = "standalone";

    /** Redis Cluster 模式。 */
    private static final String CLUSTER_MODE = "cluster";

    /** Redis 地址中的主机和端口分隔符。 */
    private static final char ADDRESS_SEPARATOR = ':';

    /** Redis 物理 Key namespace 分隔符。 */
    private static final String KEY_PREFIX_SEPARATOR = ":";

    /** Redis 连接模式，支持 standalone 或 cluster。 */
    private String mode = STANDALONE_MODE;

    /** 逗号分隔的 Redis host:port 地址列表。 */
    private String addresses = "localhost:6379";

    /** Redis ACL 用户名，为空时不设置。 */
    private String username = "";

    /** Redis 认证密码，为空时不设置。 */
    private String password = "";

    /** Redis 逻辑数据库；Cluster 固定使用 0。 */
    private int database;

    /** 是否启用 Redis TLS。 */
    private boolean tls;

    /** 与 Android 进程一致的 Redis 全局 Key 前缀。 */
    private String keyPrefix = "android-zhuan:";

    /**
     * 校验 Redis 模式、地址、逻辑库和 Key 前缀，避免双方 namespace 不一致时启动。
     */
    @Override
    public void afterPropertiesSet() {
        String normalizedMode = normalizedMode();
        List<String> configuredAddresses = addressList();
        if (!STANDALONE_MODE.equals(normalizedMode) && !CLUSTER_MODE.equals(normalizedMode)) {
            throw new IllegalStateException(
                    "Android image Redis mode must be standalone or cluster");
        }
        if (configuredAddresses.isEmpty()) {
            throw new IllegalStateException("Android image Redis address is required");
        }
        if (STANDALONE_MODE.equals(normalizedMode) && configuredAddresses.size() != 1) {
            throw new IllegalStateException(
                    "Standalone Android image Redis requires exactly one address");
        }
        if (CLUSTER_MODE.equals(normalizedMode) && database != 0) {
            throw new IllegalStateException(
                    "Cluster Android image Redis requires database 0");
        }
        if (keyPrefix == null
                || keyPrefix.isBlank()
                || !keyPrefix.trim().endsWith(KEY_PREFIX_SEPARATOR)) {
            throw new IllegalStateException(
                    "Android image Redis key prefix must end with a colon");
        }
        configuredAddresses.forEach(AndroidImageRedisProperties::validateAddress);
        mode = normalizedMode;
        addresses = String.join(",", configuredAddresses);
        keyPrefix = keyPrefix.trim();
    }

    String normalizedMode() {
        if (mode == null) {
            return STANDALONE_MODE;
        }
        return mode.trim().toLowerCase(Locale.ROOT);
    }

    List<String> addressList() {
        if (addresses == null) {
            return List.of();
        }
        return Arrays.stream(addresses.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static void validateAddress(String address) {
        int separator = address.lastIndexOf(ADDRESS_SEPARATOR);
        if (separator <= 0 || separator == address.length() - 1) {
            throw invalidAddress();
        }
        try {
            int port = Integer.parseInt(address.substring(separator + 1));
            if (port <= 0 || port > 65_535) {
                throw invalidAddress();
            }
        } catch (NumberFormatException exception) {
            throw invalidAddress();
        }
    }

    private static IllegalStateException invalidAddress() {
        return new IllegalStateException("Android image Redis address must use host:port");
    }

    /**
     * 获取 Redis 连接模式。
     *
     * @return standalone 或 cluster
     */
    public String getMode() {
        return mode;
    }

    /**
     * 设置 Redis 连接模式。
     *
     * @param mode standalone 或 cluster
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * 获取 Redis 地址列表。
     *
     * @return 逗号分隔的 host:port 地址
     */
    public String getAddresses() {
        return addresses;
    }

    /**
     * 设置 Redis 地址列表。
     *
     * @param addresses 逗号分隔的 host:port 地址
     */
    public void setAddresses(String addresses) {
        this.addresses = addresses;
    }

    /**
     * 获取 Redis ACL 用户名。
     *
     * @return ACL 用户名，空字符串表示不设置
     */
    public String getUsername() {
        return username;
    }

    /**
     * 设置 Redis ACL 用户名。
     *
     * @param username ACL 用户名
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * 获取 Redis 认证密码。
     *
     * @return 认证密码，空字符串表示不设置
     */
    public String getPassword() {
        return password;
    }

    /**
     * 设置 Redis 认证密码。
     *
     * @param password Redis 认证密码
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * 获取 Redis 逻辑数据库编号。
     *
     * @return 逻辑数据库编号
     */
    public int getDatabase() {
        return database;
    }

    /**
     * 设置 Redis 逻辑数据库编号。
     *
     * @param database 逻辑数据库编号
     */
    public void setDatabase(int database) {
        this.database = database;
    }

    /**
     * 返回是否启用 Redis TLS。
     *
     * @return true 表示启用 TLS
     */
    public boolean isTls() {
        return tls;
    }

    /**
     * 设置是否启用 Redis TLS。
     *
     * @param tls true 表示启用 TLS
     */
    public void setTls(boolean tls) {
        this.tls = tls;
    }

    /**
     * 获取 Redis 全局 Key 前缀。
     *
     * @return 与 Android 进程一致的 Key 前缀
     */
    public String getKeyPrefix() {
        return keyPrefix;
    }

    /**
     * 设置 Redis 全局 Key 前缀。
     *
     * @param keyPrefix 与 Android 进程一致的 Key 前缀
     */
    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
