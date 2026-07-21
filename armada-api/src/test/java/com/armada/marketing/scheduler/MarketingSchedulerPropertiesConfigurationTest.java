package com.armada.marketing.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingSchedulerPropertiesConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MarketingSchedulerPropertiesConfiguration.class);

    @Test
    void registersAndBindsBatchPropertiesWithoutKafkaProfile() {
        contextRunner
                .withPropertyValues(
                        "armada.marketing.round-scheduler.outbox-batch-size=321",
                        "armada.marketing.round-scheduler.image-outbox-batch-size=123")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MarketingRoundSchedulerProperties properties =
                            context.getBean(MarketingRoundSchedulerProperties.class);
                    assertThat(properties.getOutboxBatchSize()).isEqualTo(321);
                    assertThat(properties.getImageOutboxBatchSize()).isEqualTo(123);
                });
    }
}
