package com.armada.account.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.impl.AccountRegistrationServiceImpl;
import com.armada.platform.registration.cobalt.CobaltRegistrationClient;
import com.armada.platform.registration.cobalt.model.CobaltHealth;
import com.armada.platform.sms.grizzly.GrizzlySmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 不允许在没有注册调度器的实例上接收付费任务。 */
class AccountRegistrationAvailabilityTest {
    private final MockEnvironment environment = new MockEnvironment();
    private final CobaltRegistrationClient cobalt = mock(CobaltRegistrationClient.class);
    private final GrizzlySmsProperties grizzly = new GrizzlySmsProperties();

    private AccountRegistrationServiceImpl service(boolean enabled) {
        grizzly.setEnabled(true);
        grizzly.setPurchasesEnabled(true);
        return new AccountRegistrationServiceImpl(null, null, null, null, grizzly, cobalt, enabled, environment);
    }

    @Test void businessDisabledRemainsFirstReason() {
        assertThat(service(false).orderingDisabledReason()).isEqualTo("REGISTRATION_DISABLED");
        verifyNoInteractions(cobalt);
    }

    @Test void missingSchedulerBlocksEvenWithKafkaProfile() {
        environment.setActiveProfiles("kafka");
        assertThat(service(true).orderingDisabledReason()).isEqualTo("REGISTRATION_SCHEDULER_DISABLED");
        verifyNoInteractions(cobalt);
    }

    @Test void schedulerFlagWithoutKafkaProfileCannotAcceptOrders() {
        environment.setProperty("armada.account.registration.scheduler.enabled", "true");
        assertThat(service(true).orderingDisabledReason()).isEqualTo("REGISTRATION_SCHEDULER_DISABLED");
        verifyNoInteractions(cobalt);
    }

    @Test void allGatesAndLiveCobaltCapacityAllowOrders() {
        environment.setActiveProfiles("kafka");
        environment.setProperty("armada.account.registration.scheduler.enabled", "true");
        when(cobalt.isEnabled()).thenReturn(true);
        when(cobalt.health()).thenReturn(new CobaltHealth(true, 1));
        assertThat(service(true).orderingDisabledReason()).isEmpty();
    }
}
