package com.armada.promotion.channel.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class PromotionDomainNormalizerTest {

    @Test
    void normalizesHttpsHostToLowercaseAsciiHost() {
        assertThat(PromotionDomainNormalizer.normalize(" https://GO.Example.COM. "))
                .isEqualTo("go.example.com");
    }

    @Test
    void rejectsPathPortAndNonHttpsScheme() {
        assertThatThrownBy(() -> PromotionDomainNormalizer.normalize("https://go.example.com/path"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许填写域名");
        assertThatThrownBy(() -> PromotionDomainNormalizer.normalize("https://go.example.com:8443"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("端口");
        assertThatThrownBy(() -> PromotionDomainNormalizer.normalize("http://go.example.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("HTTPS");
    }
}
