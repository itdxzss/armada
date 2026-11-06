package com.armada.platform.registration.go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;

/** 用本机 HTTP 替身验证 Go 适配器的请求形状与响应分类；不访问真实 Go 或 WhatsApp。 */
class GoRegistrationClientTest {
    private static final long TENANT = 7L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private GoRegistrationClient client;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> tenantHeader = new AtomicReference<>();
    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            tenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        client = new GoRegistrationClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), JSON);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void requestCodeSendsTenantWithoutBearerAndParsesWaitingCode() throws Exception {
        responseBody = envelope(0, "WAITING_CODE", 3, false, "");

        var response = client.requestCode(TENANT, "sms-go-v1:7:9:send", new GoRegistrationRequest("+12025550123", "socks5://u:p@h:1080", false));

        assertThat(response.kind()).isEqualTo(GoRegistrationResponse.Kind.DONE);
        assertThat(response.snapshot().state()).isEqualTo(GoRegistrationState.WAITING_CODE);
        assertThat(response.snapshot().version()).isEqualTo(3L);
        assertThat(response.snapshot().transactionId()).isEqualTo("2de772cb-f034-4cc2-8a0b-fbe218bcc01b");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/ws/v1/registrations/code-requests");
        assertThat(authorization.get()).isNull();
        assertThat(tenantHeader.get()).isEqualTo("7");
        assertThat(idempotencyKey.get()).isEqualTo("sms-go-v1:7:9:send");
        JsonNode body = JSON.readTree(requestBody.get());
        assertThat(body.get("phone").asText()).isEqualTo("+12025550123");
        assertThat(body.has("profile_id")).isFalse();
        assertThat(body.has("is_business")).isTrue();
        assertThat(body.path("is_business").asBoolean()).isFalse();
        assertThat(body.get("proxy").asText()).isEqualTo("socks5://u:p@h:1080");
    }

    @Test
    void businessRequestSendsTypeWithoutProfileAndWithoutExposingSecrets() throws Exception {
        responseBody = envelope(0, "WAITING_CODE", 3, false, "");
        var request = new GoRegistrationRequest("+12025550123", "socks5://u:p@h:1080", true);
        assertThat(client.requestCode(TENANT, "business", request).kind()).isEqualTo(GoRegistrationResponse.Kind.DONE);
        JsonNode body = JSON.readTree(requestBody.get());
        assertThat(body.has("profile_id")).isFalse();
        assertThat(body.get("is_business").asBoolean()).isTrue();
        assertThat(request.toString()).doesNotContain("12025550123", "socks5", "u:p");
    }

    @Test
    void verifyCodeKeepsLeadingZeroAsStringAndSendsExpectedVersion() throws Exception {
        responseBody = envelope(0, "REGISTERED", 5, true, "");

        var response = client.verifyCode(TENANT, "sms-go-v1:7:9:verify", "2de772cb-f034-4cc2-8a0b-fbe218bcc01b",
                "004271", 3L);

        assertThat(response.kind()).isEqualTo(GoRegistrationResponse.Kind.DONE);
        assertThat(response.snapshot().resultAvailable()).isTrue();
        assertThat(authorization.get()).isNull();
        assertThat(tenantHeader.get()).isEqualTo("7");
        assertThat(path.get()).isEqualTo("/ws/v1/registrations/2de772cb-f034-4cc2-8a0b-fbe218bcc01b/code-verifications");
        JsonNode body = JSON.readTree(requestBody.get());
        assertThat(body.get("code").isTextual()).isTrue();
        assertThat(body.get("code").asText()).isEqualTo("004271");
        assertThat(body.get("expected_version").asLong()).isEqualTo(3L);
    }

    @Test
    void statusIsAReadOnlyGetWithoutBodyOrIdempotencyKey() {
        responseBody = envelope(0, "WAITING_CODE", 3, false, "");

        client.status(TENANT, "2de772cb-f034-4cc2-8a0b-fbe218bcc01b");

        assertThat(method.get()).isEqualTo("GET");
        assertThat(authorization.get()).isNull();
        assertThat(tenantHeader.get()).isEqualTo("7");
        assertThat(idempotencyKey.get()).isNull();
        assertThat(requestBody.get()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "202, OPERATION_IN_PROGRESS, IN_PROGRESS",
            "202, OUTCOME_UNKNOWN, OUTCOME_UNKNOWN",
            "202, SOMETHING_ELSE, OUTCOME_UNKNOWN",
            "429, RATE_LIMITED, RATE_LIMITED",
            "409, CONFLICT, CONFLICT",
            "503, CAPABILITY_UNAVAILABLE, UNAVAILABLE",
            "422, ACTION_REQUIRED, REJECTED",
            "401, '', REJECTED",
            "400, INVALID_INPUT, REJECTED",
            "500, PERSISTENCE_ERROR, NO_RESPONSE",
            "502, '', NO_RESPONSE"})
    void httpStatusIsClassifiedForTheCaller(int http, String errorCode, GoRegistrationResponse.Kind expected) {
        status = http;
        responseBody = envelope(1, "WAITING_CODE", 3, false, errorCode == null ? "" : errorCode);

        var response = client.requestCode(TENANT, "k", new GoRegistrationRequest("+12025550123", "socks5://u:p@h:1080", false));

        assertThat(response.kind()).isEqualTo(expected);
        assertThat(response.snapshot().errorCode()).isEqualTo(errorCode == null ? "" : errorCode);
    }

    @Test
    void successWithoutBusinessCodeZeroOrWithGarbageBodyIsNeverTreatedAsSuccess() {
        responseBody = envelope(1, "WAITING_CODE", 3, false, "");
        assertThat(client.requestCode(TENANT, "k", new GoRegistrationRequest("+1", "p", false)).kind())
                .isEqualTo(GoRegistrationResponse.Kind.OUTCOME_UNKNOWN);

        responseBody = "not json";
        assertThat(client.requestCode(TENANT, "k", new GoRegistrationRequest("+1", "p", false)).kind())
                .isEqualTo(GoRegistrationResponse.Kind.OUTCOME_UNKNOWN);
    }

    @Test
    void connectionFailureReturnsNoResponseSoCallerCanReplayWithTheSameKey() {
        server.stop(0);

        var response = client.requestCode(TENANT, "k", new GoRegistrationRequest("+12025550123", "socks5://u:p@h:1080", false));

        assertThat(response.kind()).isEqualTo(GoRegistrationResponse.Kind.NO_RESPONSE);
    }

    @Test
    void refusesInvalidTenantOrUnsafeTransactionId() {
        for (Long tenantId : new Long[]{null, 0L, -1L}) {
            assertThatThrownBy(() -> client.status(tenantId, "abc")).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> client.status(TENANT, "../admin")).isInstanceOf(BusinessException.class);
    }

    @Test
    void eachCallUsesItsPersistedTenantWithoutAnyCredential() {
        responseBody = envelope(0, "WAITING_CODE", 3, false, "");

        client.status(8L, "2de772cb-f034-4cc2-8a0b-fbe218bcc01b");
        assertThat(tenantHeader.get()).isEqualTo("8");
        assertThat(authorization.get()).isNull();
        client.status(TENANT, "2de772cb-f034-4cc2-8a0b-fbe218bcc01b");
        assertThat(tenantHeader.get()).isEqualTo("7");
        assertThat(authorization.get()).isNull();
    }

    @Test
    void propertiesRejectInvalidStartupConfiguration() {
        var badTimeout = new GoRegistrationProperties();
        badTimeout.setReadTimeout(java.time.Duration.ofSeconds(61));
        assertThatThrownBy(badTimeout::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        var tooMany = new GoRegistrationProperties();
        tooMany.setConcurrency(GoRegistrationProperties.MAX_CONCURRENCY + 1);
        assertThatThrownBy(tooMany::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        new GoRegistrationProperties().afterPropertiesSet();
    }

    private static String envelope(int code, String state, long version, boolean resultAvailable, String errorCode) {
        return "{\"Code\":" + code + ",\"Msg\":\"\",\"Data\":{\"schema_version\":1,"
                + "\"transaction_id\":\"2de772cb-f034-4cc2-8a0b-fbe218bcc01b\",\"state\":\"" + state + "\","
                + "\"version\":" + version + ",\"result_available\":" + resultAvailable
                + ",\"error_code\":\"" + errorCode + "\"}}";
    }
}
