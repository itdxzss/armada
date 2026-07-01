package com.armada.resource.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class IpProxyCheckPropertiesTest {

    @Test
    void binderLoadsExecutorAndTimeoutProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new OriginTrackedMapPropertySource(
                "test",
                Map.of(
                        "armada.ip-proxy-check.executor.core-size", "5",
                        "armada.ip-proxy-check.executor.max-size", "13",
                        "armada.ip-proxy-check.executor.queue-capacity", "777",
                        "armada.ip-proxy-check.timeout.connect-ms", "1111",
                        "armada.ip-proxy-check.timeout.read-ms", "2222",
                        "armada.ip-proxy-check.timeout.total-ms", "3333"
                )));

        IpProxyCheckProperties properties = Binder.get(environment)
                .bind("armada.ip-proxy-check", Bindable.of(IpProxyCheckProperties.class))
                .orElseThrow(IllegalStateException::new);

        assertThat(properties.getExecutor().getCoreSize()).isEqualTo(5);
        assertThat(properties.getExecutor().getMaxSize()).isEqualTo(13);
        assertThat(properties.getExecutor().getQueueCapacity()).isEqualTo(777);
        assertThat(properties.getTimeout().getConnectMs()).isEqualTo(1111);
        assertThat(properties.getTimeout().getReadMs()).isEqualTo(2222);
        assertThat(properties.getTimeout().getTotalMs()).isEqualTo(3333);
    }

    @Test
    void defaultsMatchSpec() {
        IpProxyCheckProperties properties = new IpProxyCheckProperties();

        assertThat(properties.getExecutor().getCoreSize()).isEqualTo(4);
        assertThat(properties.getExecutor().getMaxSize()).isEqualTo(12);
        assertThat(properties.getExecutor().getQueueCapacity()).isEqualTo(5_000);
        assertThat(properties.getTimeout().getConnectMs()).isEqualTo(5_000);
        assertThat(properties.getTimeout().getReadMs()).isEqualTo(8_000);
        assertThat(properties.getTimeout().getTotalMs()).isEqualTo(15_000);
    }
}
