package com.armada.platform.protocol.http;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolHttpExecutorRegistryTest {

    @Test
    void returnsExecutorForEachRegisteredBackendAndRejectsMissingBackend() {
        ProtocolHttpExecutor web = executor("http://web.internal");
        ProtocolHttpExecutor android = executor("http://android.internal");
        ProtocolHttpExecutorRegistry registry = new ProtocolHttpExecutorRegistry(Map.of(
                ProtocolBackend.WEB, web,
                ProtocolBackend.ANDROID, android));

        assertThat(registry.required(ProtocolBackend.WEB)).isSameAs(web);
        assertThat(registry.required(ProtocolBackend.ANDROID)).isSameAs(android);
        assertThatThrownBy(() -> new ProtocolHttpExecutorRegistry(Map.of(ProtocolBackend.WEB, web))
                .required(ProtocolBackend.ANDROID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANDROID");
    }

    private static ProtocolHttpExecutor executor(String baseUrl) {
        return new ProtocolHttpExecutor(RestClient.builder().baseUrl(baseUrl).build());
    }
}
