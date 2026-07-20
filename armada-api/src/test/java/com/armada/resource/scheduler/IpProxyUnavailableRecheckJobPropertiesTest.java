package com.armada.resource.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

class IpProxyUnavailableRecheckJobPropertiesTest {

    @Test
    void binderLoadsUnavailableRecheckProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new OriginTrackedMapPropertySource(
                "test",
                Map.of(
                        "armada.ip-proxy-unavailable-recheck.enabled", "false",
                        "armada.ip-proxy-unavailable-recheck.fixed-delay-ms", "1234",
                        "armada.ip-proxy-unavailable-recheck.batch-size", "500"
                )));

        IpProxyUnavailableRecheckJobProperties properties = Binder.get(environment)
                .bind("armada.ip-proxy-unavailable-recheck",
                        Bindable.of(IpProxyUnavailableRecheckJobProperties.class))
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.fixedDelayMs()).isEqualTo(1234L);
        assertThat(properties.batchSize()).isEqualTo(500);
    }

    @Test
    void defaultsMatchSpec() {
        IpProxyUnavailableRecheckJobProperties properties = new IpProxyUnavailableRecheckJobProperties();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.fixedDelayMs()).isEqualTo(900_000L);
        assertThat(properties.batchSize()).isEqualTo(200);
    }

    @Test
    void applicationYamlDefaultsBatchSizeToTwoHundred() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty(
                "armada.ip-proxy-unavailable-recheck.batch-size", Integer.class))
                .isEqualTo(200);
    }
}
