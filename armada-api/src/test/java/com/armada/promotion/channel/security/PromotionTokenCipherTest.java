package com.armada.promotion.channel.security;

import static org.assertj.core.api.Assertions.assertThat;

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
}
