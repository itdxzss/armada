package com.armada.boot.config;

import com.armada.platform.sms.grizzly.GrizzlySmsClient;
import com.armada.platform.sms.grizzly.GrizzlySmsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用回环 HTTP 服务验证生产 transport 不会重复执行 GET 购买请求。 */
class GrizzlySmsConfigurationTest {

    private HttpServer server;
    private CloseableHttpClient httpClient;
    private RestClient restClient;

    @BeforeEach
    void startLocalServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        GrizzlySmsProperties properties = new GrizzlySmsProperties();
        properties.setReadTimeout(Duration.ofMillis(250));
        httpClient = new GrizzlySmsConfiguration().grizzlySmsHttpClient(properties);
        restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }

    @AfterEach
    void closeLocalServer() throws IOException {
        httpClient.close();
        server.stop(0);
    }

    @Test
    void doesNotReplayGetAfterServerClosesConnectionWithoutResponse() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/purchase", exchange -> {
            requests.incrementAndGet();
            exchange.close();
        });

        assertThatThrownBy(() -> restClient.get().uri("/purchase").retrieve().body(String.class))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void doesNotReplayGetAfterServiceUnavailableWithRetryAfterHeader() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/purchase", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> restClient.get().uri("/purchase").retrieve().body(String.class))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void doesNotFollowRedirectThatCouldForwardCredentials() {
        AtomicInteger destinationRequests = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/destination?api_key=test-only-key");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/destination", exchange -> {
            destinationRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        assertThat(restClient.get().uri("/redirect").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(302);
        assertThat(destinationRequests.get()).isZero();
    }

    @Test
    void springCreatesDisabledClientWithoutCredentialsOrNetworkAccess() {
        new ApplicationContextRunner()
                .withUserConfiguration(GrizzlySmsConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(GrizzlySmsClient.class);
                    assertThat(context.getBean(GrizzlySmsProperties.class).isEnabled()).isFalse();
                    assertThatThrownBy(context.getBean(GrizzlySmsClient.class)::getBalance)
                            .isInstanceOf(com.armada.shared.exception.BusinessException.class);
                });
    }

    @Test
    void springBindsQueriesSeparatelyFromOrderMutations() {
        new ApplicationContextRunner()
                .withUserConfiguration(GrizzlySmsConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("armada.sms.grizzly.enabled=true",
                        "armada.sms.grizzly.api-key=test-only-key", "armada.sms.grizzly.read-timeout=3s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GrizzlySmsProperties properties = context.getBean(GrizzlySmsProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.isPurchasesEnabled()).isFalse();
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(3));
                });
    }
}
