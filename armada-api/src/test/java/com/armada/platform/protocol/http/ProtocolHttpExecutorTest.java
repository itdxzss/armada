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

        String notOwnerBody = """
                {
                  "code": "NOT_OWNER",
                  "message": "request must be retried on owner worker",
                  "details": {
                    "retryAfterMs": 1500,
                    "ownerEndpoint": "http://owner.internal:3000"
                  }
                }
                """;
        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_001/online"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(notOwnerBody));
        // NOT_OWNER 会触发一次 owner 重投；owner 仍回 NOT_OWNER 时才把元数据抛给调用方。
        server.expect(requestTo("http://owner.internal:3000/v1/accounts/acc_001/online"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(notOwnerBody));

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
    void sensitiveErrorNeverIncludesProtocolMessageOrBody() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());
        String secret = "private-baileys-credential";

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_001/export/baileys-json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"" + secret + "\",\"details\":{\"creds\":\"" + secret + "\"}}"));

        assertThatThrownBy(() -> executor.getSensitiveTyped(
                "/v1/accounts/{accountId}/export/baileys-json", PingResponse.class, "acc_001"))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.httpStatus()).isEqualTo(502);
                    assertThat(ex.getMessage()).doesNotContain(secret, "creds", "details");
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
                    assertThat(ex.retryable()).isEmpty();
                    assertThat(ex.getMessage()).contains("account reachout restricted while joining group");
                });
        server.verify();
    }

    @Test
    void mapsPermanentGroupJoinErrorAndRetryability() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/groups/join"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.GONE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "code": "INVITE_REVOKED",
                                  "message": "invite was revoked",
                                  "retryable": false,
                                  "details": {
                                    "rawCode": 410
                                  }
                                }
                                """));

        assertThatThrownBy(() -> executor.postTyped(
                "/v1/groups/join",
                Map.of("accountId", "acc_1", "inviteCode", "REVOKED"),
                PingResponse.class))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.INVITE_REVOKED);
                    assertThat(ex.protocolCode()).contains("INVITE_REVOKED");
                    assertThat(ex.retryable()).contains(false);
                });
        server.verify();
    }

    @Test
    void normalizesHyphenatedLowercaseProtocolCodeAndPreservesRawCode() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/groups/join"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "code": "bad-request",
                                  "message": "bad"
                                }
                                """));

        assertThatThrownBy(() -> executor.postTyped(
                "/v1/groups/join",
                Map.of("accountId", "acc_001", "inviteCode", "INVALID"),
                PingResponse.class))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.BAD_REQUEST);
                    assertThat(ex.httpStatus()).isEqualTo(400);
                    assertThat(ex.protocolCode()).contains("bad-request");
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

    @Test
    void retriesOnceAgainstOwnerEndpointWhenNotOwner() {
        // 多机器部署后账号会在 worker 之间迁移。迁移窗口内落到旧 owner 的请求会拿到
        // NOT_OWNER + 新 owner 地址，防腐层必须自己改打新地址，否则表现为偶发失败。
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://protocol.internal")
                .defaultHeader(ProtocolHttpExecutor.API_KEY_HEADER, "secret-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_001/probe"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "code": "NOT_OWNER",
                                  "message": "account acc_001 is not owned by this worker",
                                  "details": { "ownerEndpoint": "http://owner.internal:8082" }
                                }
                                """));
        // 重试必须打到 owner worker 的绝对地址，并且原样保留 path 和鉴权头。
        server.expect(requestTo("http://owner.internal:8082/v1/accounts/acc_001/probe"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(ProtocolHttpExecutor.API_KEY_HEADER, "secret-key"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        PingResponse result = executor.postTyped(
                "/v1/accounts/{accountId}/probe", null, PingResponse.class, "acc_001");

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void retriesOwnerEndpointForTemplatedGetRequests() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_002/status"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_OWNER\",\"details\":{\"ownerEndpoint\":\"http://owner.internal:8083\"}}"));
        server.expect(requestTo("http://owner.internal:8083/v1/accounts/acc_002/status"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        PingResponse result = executor.getTyped(
                "/v1/accounts/{accountId}/status", PingResponse.class, "acc_002");

        assertThat(result.ok()).isTrue();
        server.verify();
    }

    @Test
    void retriesOwnerEndpointAtMostOnce() {
        // owner 也回 NOT_OWNER 说明归属还在变。这里必须停下抛错，不能顺着 endpoint 一直跳。
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_003/probe"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_OWNER\",\"details\":{\"ownerEndpoint\":\"http://owner-a.internal:8082\"}}"));
        server.expect(requestTo("http://owner-a.internal:8082/v1/accounts/acc_003/probe"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_OWNER\",\"details\":{\"ownerEndpoint\":\"http://owner-b.internal:8084\"}}"));

        assertThatThrownBy(() -> executor.postTyped(
                "/v1/accounts/{accountId}/probe", null, PingResponse.class, "acc_003"))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.NOT_OWNER);
                    // 抛出的必须是第二跳的元数据，让调用方看到最新的 owner 线索。
                    assertThat(ex.ownerEndpoint()).contains("http://owner-b.internal:8084");
                });
        server.verify();
    }

    @Test
    void doesNotRetryWhenNotOwnerHasNoOwnerEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_004/probe"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_OWNER\",\"message\":\"owner unknown\"}"));

        assertThatThrownBy(() -> executor.postTyped(
                "/v1/accounts/{accountId}/probe", null, PingResponse.class, "acc_004"))
                .isInstanceOfSatisfying(ProtocolException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.NOT_OWNER));
        server.verify();
    }

    @Test
    void doesNotRetryWhenOwnerEndpointIsNotAnHttpUrl() {
        // ownerEndpoint 来自下游响应，不能无条件当成请求目标。
        // 非 http(s) 或无 host 的值一律忽略，避免把它当地址拼出去。
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_005/probe"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_OWNER\",\"details\":{\"ownerEndpoint\":\"file:///etc/passwd\"}}"));

        assertThatThrownBy(() -> executor.postTyped(
                "/v1/accounts/{accountId}/probe", null, PingResponse.class, "acc_005"))
                .isInstanceOfSatisfying(ProtocolException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.NOT_OWNER));
        server.verify();
    }

    @Test
    void doesNotRetryNonNotOwnerErrors() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_006/probe"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"RATE_LIMITED\",\"details\":{\"retryAfterMs\":1000}}"));

        assertThatThrownBy(() -> executor.postTyped(
                "/v1/accounts/{accountId}/probe", null, PingResponse.class, "acc_006"))
                .isInstanceOfSatisfying(ProtocolException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.RATE_LIMITED));
        server.verify();
    }

    record PingResponse(boolean ok) {
    }
}
