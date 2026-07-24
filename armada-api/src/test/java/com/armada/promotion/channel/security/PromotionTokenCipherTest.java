package com.armada.promotion.channel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class PromotionTokenCipherTest {

    @Test
    void springCreatesCipherUsingConfiguredProductionConstructor() {
        String configuredKey = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // 使用与 application.yml 相同的配置项，真实验证 Spring 能选择生产构造器并完成属性注入。
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "promotionTokenCipherTest",
                    Map.of(
                            "armada.promotion.tracking.encryption-key", configuredKey,
                            "armada.promotion.tracking.encryption-key-id", "test-key-v1")));
            context.register(PromotionTokenCipher.class);

            context.refresh();

            PromotionTokenCipher.EncryptedToken encrypted = context
                    .getBean(PromotionTokenCipher.class)
                    .encrypt("spring-managed-token");
            assertThat(encrypted.ciphertext()).isNotEmpty();
            assertThat(encrypted.keyId()).isEqualTo("test-key-v1");
        }
    }

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

    @Test
    void decryptsCiphertextWithMatchingKeyVersion() {
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        PromotionTokenCipher cipher = new PromotionTokenCipher(key, "test-key-v1");
        PromotionTokenCipher.EncryptedToken encrypted = cipher.encrypt("secret-access-token");

        String plaintext = cipher.decrypt(
                encrypted.ciphertext(), encrypted.keyId(), encrypted.fingerprint());

        assertThat(plaintext).isEqualTo("secret-access-token");
    }

    @Test
    void decryptRejectsMismatchedKeyVersionAndTamperedCiphertext() {
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        PromotionTokenCipher cipher = new PromotionTokenCipher(key, "test-key-v1");
        PromotionTokenCipher.EncryptedToken encrypted = cipher.encrypt("secret-access-token");

        assertThatThrownBy(() -> cipher.decrypt(
                encrypted.ciphertext(), "old-key", encrypted.fingerprint()))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("密钥版本");

        encrypted.ciphertext()[encrypted.ciphertext().length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(
                encrypted.ciphertext(), encrypted.keyId(), encrypted.fingerprint()))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void decryptRejectsCiphertextMovedToRowWithDifferentFingerprint() {
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        PromotionTokenCipher cipher = new PromotionTokenCipher(key, "test-key-v1");
        PromotionTokenCipher.EncryptedToken encrypted = cipher.encrypt("secret-access-token");
        byte[] anotherRowFingerprint = cipher.encrypt("another-token").fingerprint();

        assertThatThrownBy(() -> cipher.decrypt(
                encrypted.ciphertext(), encrypted.keyId(), anotherRowFingerprint))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("指纹");
    }
}
