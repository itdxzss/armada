package com.armada.promotion.channel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.promotion.channel.service.FacebookCapiClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpFacebookCapiClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsFormalEventWithHashedPhoneAndWithoutTestCodeOrPlainPhone() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"events_received\":1}");
        });

        FacebookCapiClient.Result result = client().send(businessCommand());

        assertThat(result.success()).isTrue();
        assertThat(result.retryable()).isFalse();
        assertThat(authorization.get()).isEqualTo("Bearer secret-token");
        assertThat(body.get())
                .contains("\"event_name\":\"Lead\"")
                .contains("\"event_id\":\"capi_123\"")
                .contains("\"ph\":[\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"]")
                .contains("\"client_ip_address\":\"203.0.113.9\"")
                .contains("\"fbp\":\"fb.1.1700000000000.1\"")
                .doesNotContain("test_event_code", "919876543210", "secret-token");
    }

    @Test
    void preservesLegacyProbePayloadButClassifiesProductionHttpFailures() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 429, "{\"error\":{\"message\":\"secret-token\"}}");
        });

        FacebookCapiClient.Result result = client().probe(probeCommand());

        assertThat(body.get()).contains("\"test_event_code\":\"TEST12345\"");
        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(result.errorMessage()).doesNotContain("secret-token");
    }

    @Test
    void productionConfigurationRejectsNonMetaOrInsecureEndpoint() {
        assertThatThrownBy(() -> new HttpFacebookCapiClient(
                RestClient.builder(), "http://attacker.example", "v22.0", 1_000, 1_000, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("官方 HTTPS");
    }

    @Test
    void commandToStringRedactsTokenAndAllMatchingData() {
        assertThat(businessCommand().toString())
                .doesNotContain("secret-token", "203.0.113.9", "aaaaaaaa");
    }

    private HttpFacebookCapiClient client() {
        return new HttpFacebookCapiClient(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "v22.0", 1_000, 1_000, true);
    }

    private static FacebookCapiClient.ProbeCommand probeCommand() {
        return new FacebookCapiClient.ProbeCommand(
                "123456789012345", "secret-token", "TEST12345",
                "https://go.example.com/code", "PageView", "probe_123",
                1_784_692_800L, "hashed-probe-user");
    }

    private static FacebookCapiClient.BusinessEventCommand businessCommand() {
        return new FacebookCapiClient.BusinessEventCommand(
                "123456789012345", "secret-token", "https://go.example.com/code",
                "Lead", "capi_123", 1_784_692_800L,
                "a".repeat(64), "203.0.113.9", "Mozilla/5.0",
                "fb.1.1700000000000.1", "fb.1.1700000000000.CLICK");
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v22.0/123456789012345/events", handler::handle);
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
