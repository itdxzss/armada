package com.armada.platform.protocol.http.pairing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.PairingCodeCommand;
import com.armada.platform.protocol.model.command.ProxyDescriptor;
import com.armada.platform.protocol.port.PairingLoginPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpPairingLoginAdapterTest {

    @Test
    void requestPairingCodeLetsProtocolGenerateRandomCode() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PairingLoginPort port = new HttpPairingLoginAdapter(
                new ProtocolHttpExecutor(builder.build()), new ObjectMapper());

        server.expect(requestTo("http://protocol.internal/v1/auth/promotion-pairing-code"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId":"acc_pair_7d9ca2f10b8e4c31",
                          "phone":"919876543210",
                          "proxy":{
                            "protocol":"socks5",
                            "url":"socks5://user:pass@proxy.internal:1080",
                            "sessionId":"sticky-001",
                            "country":"IN"
                          }
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "accountId":"acc_pair_7d9ca2f10b8e4c31",
                          "pairingId":"pairing-001",
                          "expiresAt":"2027-01-15T08:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = port.requestCode(new PairingCodeCommand(
                "acc_pair_7d9ca2f10b8e4c31",
                "919876543210",
                new ProxyDescriptor(
                        "socks5",
                        "socks5://user:pass@proxy.internal:1080",
                        "sticky-001",
                        "IN")));

        assertThat(result.protocolAccountId()).isEqualTo("acc_pair_7d9ca2f10b8e4c31");
        assertThat(result.pairingId()).isEqualTo("pairing-001");
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2027-01-15T08:00:00Z"));
        server.verify();
    }

    @Test
    void exportReturnsCompleteBaileysCredentialObject() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PairingLoginPort port = new HttpPairingLoginAdapter(
                new ProtocolHttpExecutor(builder.build()), new ObjectMapper());

        server.expect(requestTo(
                        "http://protocol.internal/v1/accounts/acc_919876543210/export/baileys-json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"schema":"baileys.auth_state.v1","creds":{"me":{"id":"x"}},"keys":{}}
                        """, MediaType.APPLICATION_JSON));

        var result = port.exportCredential("acc_919876543210");

        assertThat(result.protocolAccountId()).isEqualTo("acc_919876543210");
        assertThat(result.credentialJson()).contains("\"creds\"").contains("\"keys\"");
        server.verify();
    }

    @Test
    void exportRejectsIncompleteBaileysCredentialObject() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PairingLoginPort port = new HttpPairingLoginAdapter(
                new ProtocolHttpExecutor(builder.build()), new ObjectMapper());

        server.expect(requestTo(
                        "http://protocol.internal/v1/accounts/acc_919876543210/export/baileys-json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"schema\":\"baileys.auth_state.v1\",\"creds\":null,\"keys\":{}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> port.exportCredential("acc_919876543210"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("协议层未返回完整配对凭据");
        server.verify();
    }
}
