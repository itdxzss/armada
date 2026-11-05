package com.armada.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.registration.go.GoRegistrationClient;
import com.armada.platform.registration.go.GoRegistrationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;

/** 证明 application.yml 里记录的环境变量名真的能绑定到配置；变量名写错部署时只会静默变成“未配置”。 */
class GoRegistrationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GoRegistrationConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void tenantProfilesBindWithoutCredentials() {
        runner.withInitializer(context -> TestPropertyValues.of(
                "ARMADA_ACCOUNT_SMSREGISTRATION_ENABLED=true",
                "ARMADA_ACCOUNT_SMSREGISTRATION_BASEURL=http://go.internal:8080",
                "ARMADA_ACCOUNT_SMSREGISTRATION_CONCURRENCY=16",
                "ARMADA_ACCOUNT_SMSREGISTRATION_TENANTS_7_PROFILEID=42",
                "ARMADA_ACCOUNT_SMSREGISTRATION_TENANTS_7_BUSINESSPROFILEID=43")
                .applyTo(context.getEnvironment(), TestPropertyValues.Type.SYSTEM_ENVIRONMENT))
                .run((AssertableApplicationContext context) -> {
                    assertThat(context).hasNotFailed().hasSingleBean(GoRegistrationClient.class);
                    var properties = context.getBean(GoRegistrationProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getConcurrency()).isEqualTo(16);
                    assertThat(properties.tenant(7L)).isPresent();
                    assertThat(properties.tenant(7L).orElseThrow().getProfileId()).isEqualTo(42L);
                    assertThat(properties.tenant(7L).orElseThrow().getBusinessProfileId()).isEqualTo(43L);
                    // 没配置的租户不能使用自动注册。
                    assertThat(properties.tenant(8L)).isEmpty();
                });
    }

    @Test
    void businessOnlyTenantIsValidButReusingTheSameProfileForBothTypesFails() {
        var configured = runner.withPropertyValues("armada.account.sms-registration.enabled=true",
                "armada.account.sms-registration.base-url=http://go.internal:8080",
                "armada.account.sms-registration.tenants[7].business-profile-id=43");
        configured.run(context -> assertThat(context).hasNotFailed());
        configured.withPropertyValues("armada.account.sms-registration.tenants[7].profile-id=43")
                .run(context -> assertThat(context).hasFailed());
        configured.withPropertyValues("armada.account.sms-registration.tenants[7].profile-id=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void defaultsKeepTheFeatureOffAndEnabledWithoutProfilesFailsStartup() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GoRegistrationProperties.class).isEnabled()).isFalse();
        });
        runner.withPropertyValues("armada.account.sms-registration.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
