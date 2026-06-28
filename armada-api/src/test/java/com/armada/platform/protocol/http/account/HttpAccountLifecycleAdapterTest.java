package com.armada.platform.protocol.http.account;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.BatchOnlineCommand;
import com.armada.platform.protocol.model.command.BatchOnlineCommandItem;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.OnlineCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.BatchOnlineAccepted;
import com.armada.platform.protocol.model.result.BatchOnlineItemResult;
import com.armada.platform.protocol.model.result.BatchOnlineResultStatus;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.ProtocolAccountStatus;
import com.armada.platform.protocol.model.result.ProtocolProbeResult;
import com.armada.platform.protocol.model.result.StateSource;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpAccountLifecycleAdapterTest {

    @Test
    void onlinePostsLoadConnectBodyAndMapsAcceptedResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountLifecyclePort port = new HttpAccountLifecycleAdapter(
                new ProtocolHttpExecutor(builder.build()));
        OnlineCommand command = new OnlineCommand(
                CredentialFormat.BAILEYS_JSON,
                "{\"creds\":{\"noiseKey\":\"n\"},\"keys\":{}}",
                new ProxyDescriptor(
                        "socks5",
                        "socks5://user:pass@proxy.internal:1080",
                        "sticky-001",
                        "US"));

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_001/online"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "format": "baileys_json",
                          "credential": {
                            "creds": { "noiseKey": "n" },
                            "keys": {}
                          },
                          "proxy": {
                            "protocol": "socks5",
                            "url": "socks5://user:pass@proxy.internal:1080",
                            "sessionId": "sticky-001",
                            "country": "US"
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "accountId": "acc_001",
                          "accepted": true,
                          "stateSource": "MANUAL_REFRESH",
                          "syncedAt": "2026-06-26T10:15:30Z",
                          "routing": {
                            "ownerWorkerId": "worker-a",
                            "ownerEndpoint": null,
                            "currentWorkerId": "worker-a",
                            "local": true
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        OnlineAccepted result = port.online("acc_001", command);

        assertThat(result.protocolAccountId()).isEqualTo("acc_001");
        assertThat(result.accepted()).isTrue();
        assertThat(result.stateSource()).isEqualTo(StateSource.MANUAL_REFRESH);
        assertThat(result.syncedAt()).isEqualTo(Instant.parse("2026-06-26T10:15:30Z"));
        assertThat(result.routing().ownerWorkerId()).isEqualTo("worker-a");
        assertThat(result.routing().ownerEndpoint()).isNull();
        assertThat(result.routing().currentWorkerId()).isEqualTo("worker-a");
        assertThat(result.routing().local()).isTrue();
        server.verify();
    }

    @Test
    void onlineMapsUnknownStateSourceToUnknownEnum() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountLifecyclePort port = new HttpAccountLifecycleAdapter(
                new ProtocolHttpExecutor(builder.build()));
        OnlineCommand command = new OnlineCommand(
                CredentialFormat.PARAMS,
                "{\"login\":\"raw\"}",
                new ProxyDescriptor("http", "http://proxy.internal:8080", "sticky-002", "SG"));

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_002/online"))
                .andRespond(withSuccess("""
                        {
                          "accountId": "acc_002",
                          "accepted": true,
                          "stateSource": "NEW_PROTOCOL_SOURCE",
                          "syncedAt": "2026-06-26T10:15:31Z",
                          "routing": {
                            "ownerWorkerId": "worker-b",
                            "ownerEndpoint": "http://worker-b.internal:3000",
                            "currentWorkerId": "worker-a",
                            "local": false
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        OnlineAccepted result = port.online("acc_002", command);

        assertThat(result.stateSource()).isEqualTo(StateSource.UNKNOWN);
        assertThat(result.routing().ownerEndpoint()).isEqualTo("http://worker-b.internal:3000");
        server.verify();
    }

    @Test
    void onlineBatchPostsOneBatchRequestAndMapsAcceptedResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountLifecyclePort port = new HttpAccountLifecycleAdapter(new ProtocolHttpExecutor(builder.build()));
        BatchOnlineCommand command = new BatchOnlineCommand(List.of(
                new BatchOnlineCommandItem("acc_001", new OnlineCommand(
                        CredentialFormat.BAILEYS_JSON,
                        "{\"creds\":{\"noiseKey\":\"n1\"},\"keys\":{}}",
                        new ProxyDescriptor("socks5", "socks5://user:pass@proxy-a:1080", "sticky-001", "IN"))),
                new BatchOnlineCommandItem("acc_002", new OnlineCommand(
                        CredentialFormat.PARAMS,
                        "{\"login\":\"raw\"}",
                        new ProxyDescriptor("http", "http://user:pass@proxy-b:8080", "sticky-002", "SG")))
        ), 60_000);

        server.expect(requestTo("http://protocol.internal/v1/accounts/online/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "maxWaitMs": 60000,
                          "items": [
                            {
                              "accountId": "acc_001",
                              "format": "baileys_json",
                              "credential": {"creds": {"noiseKey": "n1"}, "keys": {}},
                              "proxy": {
                                "protocol": "socks5",
                                "url": "socks5://user:pass@proxy-a:1080",
                                "sessionId": "sticky-001",
                                "country": "IN"
                              }
                            },
                            {
                              "accountId": "acc_002",
                              "format": "params",
                              "credential": {"login": "raw"},
                              "proxy": {
                                "protocol": "http",
                                "url": "http://user:pass@proxy-b:8080",
                                "sessionId": "sticky-002",
                                "country": "SG"
                              }
                            }
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "requestedAt": "2026-06-27T10:00:00Z",
                          "elapsedMs": 80,
                          "summary": {
                            "requested": 2,
                            "local": 2,
                            "remote": 0,
                            "accepted": 1,
                            "timeout": 1,
                            "proxyRequired": 0,
                            "error": 0
                          },
                          "results": [
                            {"accountId": "acc_001", "result": "accepted"},
                            {"accountId": "acc_002", "result": "timeout", "retryAfterMs": 5000}
                          ],
                          "remote": []
                        }
                        """, MediaType.APPLICATION_JSON));

        BatchOnlineAccepted result = port.onlineBatch(command);

        assertThat(result.summary().requested()).isEqualTo(2);
        assertThat(result.summary().accepted()).isEqualTo(1);
        assertThat(result.results()).extracting(BatchOnlineItemResult::result)
                .containsExactly(BatchOnlineResultStatus.ACCEPTED, BatchOnlineResultStatus.TIMEOUT);
        server.verify();
    }

    @Test
    void onlineRejectsMalformedCredentialJsonBeforeHttpCall() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountLifecyclePort port = new HttpAccountLifecycleAdapter(
                new ProtocolHttpExecutor(builder.build()));
        OnlineCommand command = new OnlineCommand(
                CredentialFormat.SIX_SEGMENT,
                "not-json",
                new ProxyDescriptor("socks5", "socks5://proxy.internal:1080", "sticky-003", "US"));

        assertThatThrownBy(() -> port.online("acc_003", command))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("上线凭据不是合法 JSON object");
        server.verify();
    }

    @Test
    void statusGetsProtocolSnapshot() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountLifecyclePort port = new HttpAccountLifecycleAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_001/status"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "accountId": "acc_001",
                          "state": "ONLINE",
                          "stateSource": "HEARTBEAT",
                          "accountType": "BUSINESS_STANDARD",
                          "lastStateSyncTime": "2026-06-28T10:00:00Z",
                          "cooldownUntil": null,
                          "reportedAt": "2026-06-28T10:00:01Z",
                          "needReauth": false,
                          "reauthReason": null,
                          "workerId": "worker-a"
                        }
                        """, MediaType.APPLICATION_JSON));

        ProtocolAccountStatus result = port.status("acc_001");

        assertThat(result.protocolAccountId()).isEqualTo("acc_001");
        assertThat(result.state()).isEqualTo("ONLINE");
        assertThat(result.stateSource()).isEqualTo("HEARTBEAT");
        assertThat(result.accountType()).isEqualTo("BUSINESS_STANDARD");
        assertThat(result.lastStateSyncTime()).isEqualTo(Instant.parse("2026-06-28T10:00:00Z"));
        assertThat(result.reportedAt()).isEqualTo(Instant.parse("2026-06-28T10:00:01Z"));
        assertThat(result.needReauth()).isFalse();
        assertThat(result.workerId()).isEqualTo("worker-a");
        server.verify();
    }

    @Test
    void probePostsEmptyBodyAndMapsResult() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountLifecyclePort port = new HttpAccountLifecycleAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol.internal/v1/accounts/acc_001/probe"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{}"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "probedAt": "2026-06-28T10:01:00Z",
                          "latencyMs": 186,
                          "reasonCode": "OK"
                        }
                        """, MediaType.APPLICATION_JSON));

        ProtocolProbeResult result = port.probe("acc_001");

        assertThat(result.ok()).isTrue();
        assertThat(result.probedAt()).isEqualTo(Instant.parse("2026-06-28T10:01:00Z"));
        assertThat(result.latencyMs()).isEqualTo(186L);
        assertThat(result.reasonCode()).isEqualTo("OK");
        server.verify();
    }
}
