package com.armada.task.scheduler;

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

/** 拉群角色离线等待开关及宽限时长的配置加载测试。 */
class PullTaskOfflineRoleWaitPropertiesTest {

    @Test
    void configurationRegistersTaskSettingsWithoutKafkaProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(AccountBusinessContinuityConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues(
                        "armada.task.offline-role-wait.enabled=false",
                        "armada.task.offline-role-wait.creator-grace-ms=240000")
                .run(context -> {
                    assertThat(context).hasSingleBean(PullTaskOfflineRoleWaitProperties.class);
                    PullTaskOfflineRoleWaitProperties properties =
                            context.getBean(PullTaskOfflineRoleWaitProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getCreatorGraceMs()).isEqualTo(240_000L);
                });
    }

    @Test
    void defaultsMatchOfflineRoleWaitDesign() {
        PullTaskOfflineRoleWaitProperties properties = new PullTaskOfflineRoleWaitProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getReplaceableGraceMs()).isEqualTo(30_000L);
        assertThat(properties.getCreatorGraceMs()).isEqualTo(180_000L);
    }

    @Test
    void taskSwitchCanBeDisabledByConfiguration() {
        PullTaskOfflineRoleWaitProperties properties = bind(Map.of(
                "armada.task.offline-role-wait.enabled", "false"));

        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void binderLoadsBothGraceDurations() {
        PullTaskOfflineRoleWaitProperties properties = bind(Map.of(
                "armada.task.offline-role-wait.replaceable-grace-ms", "45000",
                "armada.task.offline-role-wait.creator-grace-ms", "240000"));

        assertThat(properties.getReplaceableGraceMs()).isEqualTo(45_000L);
        assertThat(properties.getCreatorGraceMs()).isEqualTo(240_000L);
    }

    @ParameterizedTest
    @CsvSource({
            "replaceable-grace-ms, 0",
            "replaceable-grace-ms, -1",
            "creator-grace-ms, 0",
            "creator-grace-ms, -1"
    })
    void binderRejectsNonPositiveGraceDurations(String property, String value) {
        assertThatThrownBy(() -> bind(Map.of("armada.task.offline-role-wait." + property, value)))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private PullTaskOfflineRoleWaitProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("armada.task.offline-role-wait", Bindable.of(PullTaskOfflineRoleWaitProperties.class))
                .orElseThrow(IllegalStateException::new);
    }
}
