package com.armada.platform.protocol.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisAndroidImageAssetStoreTest {

    private static final Duration ASSET_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, byte[]> redis = mock(RedisTemplate.class);
    private final ValueOperations<String, byte[]> values = mock(ValueOperations.class);
    private final RedisAndroidImageAssetStore store =
            new RedisAndroidImageAssetStore(redis, "android-zhuan:");

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void existingAssetOnlyRefreshesTwentyFourHourTtl() {
        AndroidImageAsset asset = asset("image");
        when(redis.expire(asset.redisKey("android-zhuan:"), ASSET_TTL)).thenReturn(true);

        store.ensure(asset);

        verify(values, never()).setIfAbsent(any(), any(), any(Duration.class));
    }

    @Test
    void missingAssetWritesRawBytesWithTtl() {
        AndroidImageAsset asset = asset("raw-image");
        String redisKey = asset.redisKey("android-zhuan:");
        when(redis.expire(redisKey, ASSET_TTL)).thenReturn(false);
        when(values.setIfAbsent(eq(redisKey), any(), eq(ASSET_TTL))).thenReturn(true);

        store.ensure(asset);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(values).setIfAbsent(eq(redisKey), bytes.capture(), eq(ASSET_TTL));
        assertThat(bytes.getValue()).containsExactly(
                "raw-image".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void concurrentWriterCanBeResolvedBySecondTtlRefresh() {
        AndroidImageAsset asset = asset("image");
        String redisKey = asset.redisKey("android-zhuan:");
        when(redis.expire(redisKey, ASSET_TTL)).thenReturn(false, true);
        when(values.setIfAbsent(eq(redisKey), any(), eq(ASSET_TTL))).thenReturn(false);

        assertThatCode(() -> store.ensure(asset)).doesNotThrowAnyException();
    }

    @Test
    void unresolvedExpireSetRaceFailsClosed() {
        AndroidImageAsset asset = asset("image");
        String redisKey = asset.redisKey("android-zhuan:");
        when(redis.expire(redisKey, ASSET_TTL)).thenReturn(false);
        when(values.setIfAbsent(eq(redisKey), any(), eq(ASSET_TTL))).thenReturn(false);

        assertThatThrownBy(() -> store.ensure(asset))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ensure Android image asset");
    }

    private static AndroidImageAsset asset(String value) {
        return AndroidImageAsset.from(
                7L, value.getBytes(StandardCharsets.UTF_8), "image/png");
    }
}
