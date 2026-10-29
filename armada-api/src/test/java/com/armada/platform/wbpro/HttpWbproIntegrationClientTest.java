package com.armada.platform.wbpro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.platform.wbpro.model.WbproTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 签名与 WB 一致、响应环境核对、错误码透传。 */
class HttpWbproIntegrationClientTest {

    private static final String SECRET = "test-integration-secret-0123456789abcdef";
    private HttpServer server;
    private final Map<String, String> headers = new ConcurrentHashMap<>();
    private volatile String responseBody = "";
    private volatile int responseStatus = 200;
    private HttpWbproIntegrationClient client;
    private WbproTarget target;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
            headers.put("path", exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        target = new WbproTarget("test1", "WB 测试", "http://127.0.0.1:" + server.getAddress().getPort(),
                "armada-test1", SECRET);
        client = new HttpWbproIntegrationClient(HttpClient.newHttpClient(), new ObjectMapper(), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** 与 WB DeviceImportIntegrationTest 用同一组向量，两端算法不一致时两边测试都会失败。 */
    @Test
    void signatureMatchesWbVector() {
        byte[] body = "{\"memberId\":7,\"phone\":\"628123460001\",\"pairingCode\":\"ABCDEFGH\",\"importRef\":\"armada-task-1\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(HttpWbproIntegrationClient.sign(SECRET, "POST", "/api/integration/v1/pairings", "1800000000000",
                "0123456789abcdef0123456789abcdef", body))
                .isEqualTo("061d33faf5dac2abe129f292c595b96d90ed04889a064a5f3fe1585e2b102ee7");
    }

    @Test
    void signsRawPathWithQueryAndReadsMember() {
        responseBody = "{\"code\":\"OK\",\"data\":{\"envId\":\"test1\",\"member\":{\"id\":120,\"username\":\"wb_jack01\","
                + "\"status\":\"ACTIVE\",\"deviceCount\":6,\"deviceMax\":10}}}";
        var member = client.member(target, "wb_jack01");
        assertThat(member).hasValueSatisfying(value -> assertThat(value.deviceCount()).isEqualTo(6));
        assertThat(headers.get("path")).isEqualTo("/api/integration/v1/members?username=wb_jack01");
        assertThat(headers.get("x-wb-client")).isEqualTo("armada-test1");
        String expected = HttpWbproIntegrationClient.sign(SECRET, "GET", headers.get("path"),
                headers.get("x-wb-timestamp"), headers.get("x-wb-nonce"), new byte[0]);
        assertThat(headers.get("x-wb-signature")).isEqualTo(expected);
    }

    @Test
    void otherEnvironmentIsRejected() {
        responseBody = "{\"code\":\"OK\",\"data\":{\"envId\":\"wb-prod\",\"importEnabled\":true}}";
        assertThatThrownBy(() -> client.env(target)).isInstanceOf(WbproIntegrationException.class)
                .extracting("code").isEqualTo(WbproIntegrationException.ENV_MISMATCH);
    }

    @Test
    void wbErrorCodeIsPassedThrough() {
        responseStatus = 409;
        responseBody = "{\"code\":\"SHADOW_LIMIT_REACHED\",\"message\":\"每台设备最多 3 个影子\"}";
        assertThatThrownBy(() -> client.pairing(target, "0123456789abcdef0123456789abcdef"))
                .isInstanceOf(WbproIntegrationException.class)
                .extracting("code").isEqualTo("SHADOW_LIMIT_REACHED");
    }

    @Test
    void targetsParseAndRejectBadConfig() {
        WbproProperties properties = new WbproProperties();
        properties.setTargets("wb-prod|WB 生产|https://wb.example|armada-prod|" + SECRET
                + ";customer1|客户一|https://c1.example|armada-prod|" + SECRET);
        assertThat(properties.parsedTargets()).extracting(WbproTarget::envId).containsExactly("wb-prod", "customer1");
        assertThat(properties.target("customer1")).isPresent();
        assertThat(properties.parsedTargets().getFirst().toString()).doesNotContain(SECRET);

        properties.setTargets("wb-prod|WB|http://wb.example|armada-prod|" + SECRET);
        assertThatThrownBy(properties::parsedTargets).isInstanceOf(IllegalArgumentException.class);
        properties.setTargets("wb-prod|WB|https://wb.example|armada-prod|short");
        assertThatThrownBy(properties::parsedTargets).isInstanceOf(IllegalArgumentException.class);
    }
}
