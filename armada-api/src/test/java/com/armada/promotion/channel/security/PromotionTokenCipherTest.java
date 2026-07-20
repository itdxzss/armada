package com.armada.promotion.channel.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PromotionTokenCipherTest {

    @Test
    void encryptsTokenAndKeepsOnlyCiphertextKeyIdAndFingerprint() {
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        PromotionTokenCipher cipher = new PromotionTokenCipher(key, "test-key-v1");

        PromotionTokenCipher.EncryptedToken encrypted = cipher.encrypt("secret-access-token");

        assertThat(encrypted.ciphertext()).isNotEmpty();
        assertThat(new String(encrypted.ciphertext(), StandardCharsets.ISO_8859_1))
                .doesNotContain("secret-access-token");
        assertThat(encrypted.keyId()).isEqualTo("test-key-v1");
        assertThat(encrypted.fingerprint()).hasSize(32);
    }
}
