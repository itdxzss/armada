package com.armada.account.takeover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.AccountBusinessContinuityConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 自动抢登开关、熔断阈值和补偿扫描配置的加载及校验测试。 */
class AccountAutoTakeoverPropertiesTest {

    @Test
    void configurationRegistersAccountSettingsWithoutKafkaProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(AccountBusinessContinuityConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues(
                        "armada.account.auto-takeover.enabled=false",
                        "armada.account.auto-takeover.breaker-max-kicks=4")
                .run(context -> {
                    assertThat(context).hasSingleBean(AccountAutoTakeoverProperties.class);
                    AccountAutoTakeoverProperties properties =
                            context.getBean(AccountAutoTakeoverProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getBreakerMaxKicks()).isEqualTo(4);
                });
    }

    @Test
    void defaultsMatchTakeoverDesign() {
        AccountAutoTakeoverProperties properties = new AccountAutoTakeoverProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getBreakerWindowMs()).isEqualTo(600_000L);
        assertThat(properties.getBreakerMaxKicks()).isEqualTo(10);
        assertThat(properties.getScan().isEnabled()).isTrue();
        assertThat(properties.getScan().getFixedDelayMs()).isEqualTo(60_000L);
        assertThat(properties.getScan().getBatchSize()).isEqualTo(20);
        assertThat(properties.getScan().getStuckTakingOverMs()).isEqualTo(30_000L);
    }

    @Test
    void accountAndScanSwitchesCanBeDisabledByConfiguration() {
        AccountAutoTakeoverProperties properties = bind(Map.of(
                "armada.account.auto-takeover.enabled", "false",
                "armada.account.auto-takeover.scan.enabled", "false"));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getScan().isEnabled()).isFalse();
    }

    @Test
    void binderLoadsAllNumericSettings() {
        AccountAutoTakeoverProperties properties = bind(Map.of(
                "armada.account.auto-takeover.breaker-window-ms", "120000",
                "armada.account.auto-takeover.breaker-max-kicks", "4",
                "armada.account.auto-takeover.scan.fixed-delay-ms", "15000",
                "armada.account.auto-takeover.scan.batch-size", "7",
                "armada.account.auto-takeover.scan.stuck-taking-over-ms", "8000"));

        assertThat(properties.getBreakerWindowMs()).isEqualTo(120_000L);
        assertThat(properties.getBreakerMaxKicks()).isEqualTo(4);
        assertThat(properties.getScan().getFixedDelayMs()).isEqualTo(15_000L);
        assertThat(properties.getScan().getBatchSize()).isEqualTo(7);
        assertThat(properties.getScan().getStuckTakingOverMs()).isEqualTo(8_000L);
    }

    @ParameterizedTest
    @CsvSource({
            "breaker-window-ms, 0",
            "breaker-window-ms, -1",
            "breaker-max-kicks, 0",
            "breaker-max-kicks, -1",
            "scan.fixed-delay-ms, 0",
            "scan.fixed-delay-ms, -1",
            "scan.batch-size, 0",
            "scan.batch-size, -1",
            "scan.stuck-taking-over-ms, 0",
            "scan.stuck-taking-over-ms, -1"
    })
    void binderRejectsNonPositiveThresholds(String property, String value) {
        assertThatThrownBy(() -> bind(Map.of("armada.account.auto-takeover." + property, value)))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private AccountAutoTakeoverProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("armada.account.auto-takeover", Bindable.of(AccountAutoTakeoverProperties.class))
                .orElseThrow(IllegalStateException::new);
    }
}
