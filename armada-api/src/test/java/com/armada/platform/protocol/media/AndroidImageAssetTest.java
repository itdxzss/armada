package com.armada.platform.protocol.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AndroidImageAssetTest {

    @Test
    void derivesStableTenantScopedReference() {
        byte[] source = "same-image".getBytes(StandardCharsets.UTF_8);

        AndroidImageAsset first = AndroidImageAsset.from(7L, source, "image/png");
        AndroidImageAsset second = AndroidImageAsset.from(7L, source, "image/png");

        assertThat(first.identity()).isEqualTo(second.identity());
        assertThat(first.reference().sha256()).hasSize(64);
        assertThat(first.reference().sizeBytes()).isEqualTo(source.length);
        assertThat(first.reference().mimetype()).isEqualTo("image/png");
        assertThat(first.reference().transformProfile()).isEqualTo("marketing-image-v1");
        assertThat(first.redisKey("android-zhuan:"))
                .startsWith("android-zhuan:marketing:image:v1:7:");
    }

    @Test
    void rejectsMissingTenantOrMediaButDoesNotRepeatFiveHundredKilobyteGate() {
        assertThatThrownBy(() -> AndroidImageAsset.from(null, new byte[]{1}, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AndroidImageAsset.from(7L, new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AndroidImageAsset.from(7L, new byte[]{1}, " "))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] acceptedAtDispatch = new byte[500 * 1024 + 1];
        assertThat(AndroidImageAsset.from(7L, acceptedAtDispatch, "image/png")
                .reference().sizeBytes()).isEqualTo(acceptedAtDispatch.length);
    }
}
