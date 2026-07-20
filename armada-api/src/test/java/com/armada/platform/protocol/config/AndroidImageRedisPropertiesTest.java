package com.armada.platform.protocol.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AndroidImageRedisPropertiesTest {

    @Test
    void acceptsStandaloneAndClusterConfigurations() {
        AndroidImageRedisProperties standalone = properties(
                "standalone", "cache:6379", 1, "android-zhuan:");
        AndroidImageRedisProperties cluster = properties(
                "cluster", "cache-a:6379,cache-b:6379", 0, "android-zhuan-perf:");

        assertThatCode(standalone::afterPropertiesSet).doesNotThrowAnyException();
        assertThatCode(cluster::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeOrIncompatibleConfigurations() {
        assertThatThrownBy(() -> properties(
                "cluster", "cache:6379", 1, "android-zhuan:").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database 0");
        assertThatThrownBy(() -> properties(
                "standalone", " ", 0, "android-zhuan:").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("address");
        assertThatThrownBy(() -> properties(
                "standalone", "cache:6379", 0, "android-zhuan").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("colon");
    }

    private static AndroidImageRedisProperties properties(
            String mode,
            String addresses,
            int database,
            String keyPrefix) {
        AndroidImageRedisProperties properties = new AndroidImageRedisProperties();
        properties.setMode(mode);
        properties.setAddresses(addresses);
        properties.setDatabase(database);
        properties.setKeyPrefix(keyPrefix);
        return properties;
    }
}
