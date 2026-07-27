package com.armada.platform.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lettuce.core.cluster.SlotHash;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Redis Cluster 会话键槽位回归测试。 */
class RedisSessionServiceClusterSlotTest {

    /** 创建、续期和退出脚本涉及的两个键必须落在同一槽位。 */
    @Test
    void shouldPlaceSessionAndUserPointerInSameClusterSlot() throws Exception {
        String sessionKey = invokeKeyMethod("sessionKey", String.class, "token-hash");
        String userKey = invokeKeyMethod("userKey", long.class, 7L);

        assertEquals(slot(sessionKey), slot(userKey));
    }

    private static String invokeKeyMethod(String name, Class<?> argumentType, Object argument) throws Exception {
        Method method = RedisSessionService.class.getDeclaredMethod(name, argumentType);
        method.setAccessible(true);
        return (String) method.invoke(null, argument);
    }

    private static int slot(String key) {
        return SlotHash.getSlot(key.getBytes(StandardCharsets.UTF_8));
    }
}
