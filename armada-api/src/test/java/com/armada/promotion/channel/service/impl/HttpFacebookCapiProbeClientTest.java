package com.armada.promotion.channel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.promotion.channel.service.FacebookCapiProbeClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpFacebookCapiProbeClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTestEventWithBearerTokenAndSyntheticUserData() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"events_received\":1,\"messages\":[],\"fbtrace_id\":\"trace-1\"}");
        });
        HttpFacebookCapiProbeClient client = client();

        FacebookCapiProbeClient.Result result = client.probe(command());

        assertThat(result.success()).isTrue();
        assertThat(authorization.get()).isEqualTo("Bearer secret-token");
        assertThat(body.get())
                .contains("\"test_event_code\":\"TEST12345\"")
                .contains("\"event_name\":\"PageView\"")
                .contains("\"event_id\":\"probe_123\"")
                .contains("\"external_id\":[\"hashed-probe-user\"]")
                .doesNotContain("email", "phone", "secret-token");
    }

    @Test
    void mapsUnauthorizedResponseWithoutReturningPlatformBodyOrToken() throws Exception {
        startServer(exchange -> respond(
                exchange, 401,
                "{\"error\":{\"message\":\"token secret-token invalid\"}}"));
        HttpFacebookCapiProbeClient client = client();

        FacebookCapiProbeClient.Result result = client.probe(command());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOKEN_INVALID_OR_FORBIDDEN");
        assertThat(result.errorMessage())
                .isEqualTo("Access Token 无效或无 Pixel 权限")
                .doesNotContain("secret-token");
    }

    @Test
    void productionConfigurationRejectsNonMetaOrInsecureEndpoint() {
        assertThatThrownBy(() -> new HttpFacebookCapiProbeClient(
                RestClient.builder(), "http://attacker.example", "v22.0", 1_000, 1_000, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("官方 HTTPS");
    }

    @Test
    void commandToStringNeverContainsPlaintextToken() {
        assertThat(command().toString()).doesNotContain("secret-token");
    }

    private HttpFacebookCapiProbeClient client() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpFacebookCapiProbeClient(
                RestClient.builder(), baseUrl, "v22.0", 1_000, 1_000, true);
    }

    private static FacebookCapiProbeClient.Command command() {
        return new FacebookCapiProbeClient.Command(
                "123456789012345", "secret-token", "TEST12345",
                "https://go.example.com/a8k2m9qx", "PageView", "probe_123",
                1_784_692_800L, "hashed-probe-user");
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v22.0/123456789012345/events", exchange -> handler.handle(exchange));
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
