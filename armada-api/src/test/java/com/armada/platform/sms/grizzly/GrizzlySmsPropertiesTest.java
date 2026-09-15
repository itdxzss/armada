package com.armada.platform.sms.grizzly;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 配置默认不产生接码费用，配置错误不暴露密钥。 */
class GrizzlySmsPropertiesTest {

    @Test
    void defaultsRemainDisabledWithoutCredentials() {
        GrizzlySmsProperties properties = new GrizzlySmsProperties();
        properties.afterPropertiesSet();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isPurchasesEnabled()).isFalse();
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void enablingQueriesRequiresCredentialsButDoesNotEnablePurchases() {
        GrizzlySmsProperties properties = new GrizzlySmsProperties();
        properties.setEnabled(true);
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("密钥");

        properties.setApiKey("test-only-key");
        properties.afterPropertiesSet();
        assertThat(properties.isPurchasesEnabled()).isFalse();
    }

    @Test
    void validationAndDiagnosticsNeverEchoCredentials() {
        GrizzlySmsProperties properties = new GrizzlySmsProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-only-sensitive-key\n");
        assertThatThrownBy(properties::afterPropertiesSet)
                .hasMessageNotContaining("test-only-sensitive-key");
        assertThat(properties.toString()).doesNotContain("test-only-sensitive-key");
    }

    @Test
    void rejectsInfiniteAndSubMillisecondTimeouts() {
        GrizzlySmsProperties properties = new GrizzlySmsProperties();
        for (Duration invalid : new Duration[]{Duration.ZERO, Duration.ofNanos(1),
                Duration.ofSeconds(-1), Duration.ofHours(1)}) {
            properties.setReadTimeout(invalid);
            assertThatThrownBy(properties::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void mutationGateCannotBeEnabledWhileProviderIsDisabled() {
        GrizzlySmsProperties properties = new GrizzlySmsProperties();
        properties.setPurchasesEnabled(true);
        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class);
    }
}
