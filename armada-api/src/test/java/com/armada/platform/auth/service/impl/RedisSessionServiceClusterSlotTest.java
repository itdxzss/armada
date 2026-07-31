package com.armada.platform.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.armada.platform.auth.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.cluster.SlotHash;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis Cluster 会话键槽位回归测试。 */
class RedisSessionServiceClusterSlotTest {

    /** 创建、续期和退出脚本涉及的两个键必须落在同一槽位。 */
    @Test
    void shouldPlaceSessionAndUserPointerInSameClusterSlot() {
        RedisSessionService service = service("armada:test1:");
        String sessionKey = service.sessionKey("token-hash");
        String userKey = service.userKey(7L);

        assertEquals(slot(sessionKey), slot(userKey));
    }

    @Test
    void shouldIsolateSameUserAcrossEnvironments() {
        RedisSessionService test1 = service("armada:test1:");
        RedisSessionService perf2 = service("armada:perf2:");

        assertNotEquals(test1.userKey(7L), perf2.userKey(7L));
        assertNotEquals(test1.sessionKey("token-hash"), perf2.sessionKey("token-hash"));
    }

    private static RedisSessionService service(String keyPrefix) {
        AuthProperties properties = new AuthProperties();
        properties.setSessionKeyPrefix(keyPrefix);
        return new RedisSessionService(
                new StringRedisTemplate(), new ObjectMapper(), properties, Clock.systemUTC());
    }

    private static int slot(String key) {
        return SlotHash.getSlot(key.getBytes(StandardCharsets.UTF_8));
    }
}
