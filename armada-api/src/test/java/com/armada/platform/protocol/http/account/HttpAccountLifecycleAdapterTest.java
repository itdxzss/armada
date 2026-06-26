package com.armada.platform.protocol.http.account;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.OnlineCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.model.result.OnlineAccepted;
import com.armada.platform.protocol.model.result.StateSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

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
}
