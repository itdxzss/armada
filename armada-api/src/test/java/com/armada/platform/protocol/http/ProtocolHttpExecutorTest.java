package com.armada.platform.protocol.http;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProtocolHttpExecutorTest {

    @Test
    void getTypedUsesBaseUrlAndApiKeyHeader() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://protocol.internal")
                .defaultHeader(ProtocolHttpExecutor.API_KEY_HEADER, "secret-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/ping"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(ProtocolHttpExecutor.API_KEY_HEADER, "secret-key"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        PingResponse response = executor.getTyped("/v1/ping", PingResponse.class);

        assertThat(response.ok()).isTrue();
        server.verify();
    }

    @Test
    void postTypedWithNullBodySendsEmptyJsonObject() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/probe"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{}"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        PingResponse response = executor.postTyped("/v1/probe", null, PingResponse.class);

        assertThat(response.ok()).isTrue();
        server.verify();
    }

    @Test
    void mapsProtocolErrorBodyToProtocolException() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_001/online"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "code": "NOT_OWNER",
                                  "message": "request must be retried on owner worker",
                                  "details": {
                                    "retryAfterMs": 1500,
                                    "ownerEndpoint": "http://owner.internal:3000"
                                  }
                                }
                                """));

        assertThatThrownBy(() -> executor.postTyped(
                "/v1/accounts/acc_001/online",
                Map.of("credential", "redacted"),
                PingResponse.class))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.NOT_OWNER);
                    assertThat(ex.httpStatus()).isEqualTo(409);
                    assertThat(ex.protocolCode()).contains("NOT_OWNER");
                    assertThat(ex.retryAfterMs()).contains(1_500L);
                    assertThat(ex.ownerEndpoint()).contains("http://owner.internal:3000");
                    assertThat(ex.getMessage()).contains("request must be retried on owner worker");
                });
        server.verify();
    }

    @Test
    void mapsAccountReachoutRestrictedProtocolCode() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/groups/join"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "code": "ACCOUNT_REACHOUT_RESTRICTED",
                                  "message": "account reachout restricted while joining group",
                                  "details": {
                                    "rawMessage": "account_reachout_restricted",
                                    "waCode": 463
                                  }
                                }
                                """));

        assertThatThrownBy(() -> executor.postTyped(
                "/v1/groups/join",
                Map.of("accountId", "acc_244938583362", "inviteCode", "B9gxXGEppjgHv2QZGhDpzl"),
                PingResponse.class))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED);
                    assertThat(ex.httpStatus()).isEqualTo(422);
                    assertThat(ex.protocolCode()).contains("ACCOUNT_REACHOUT_RESTRICTED");
                    assertThat(ex.getMessage()).contains("account reachout restricted while joining group");
                });
        server.verify();
    }

    @Test
    void closesResponseAfterExchangeCallbackReadsBody() {
        AtomicBoolean closed = new AtomicBoolean(false);
        MockClientHttpResponse response = new MockClientHttpResponse(
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                HttpStatus.OK) {
            @Override
            public void close() {
                closed.set(true);
                super.close();
            }
        };
        RestClient restClient = RestClient.builder()
                .baseUrl("http://protocol.internal")
                .requestFactory((uri, httpMethod) -> {
                    MockClientHttpRequest request = new MockClientHttpRequest(httpMethod, uri);
                    request.setResponse(response);
                    return request;
                })
                .build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(restClient);

        PingResponse result = executor.getTyped("/v1/ping", PingResponse.class);

        assertThat(result.ok()).isTrue();
        assertThat(closed).isTrue();
    }

    record PingResponse(boolean ok) {
    }
}
