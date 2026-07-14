package com.armada.platform.protocol.backend.web;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class WebAccountRuntimeStatusAdapterTest {

    @Test
    void getsWebRuntimeStateFromTheExistingStatusEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://web.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebAccountRuntimeStatusAdapter adapter = new WebAccountRuntimeStatusAdapter(
                new ProtocolHttpExecutor(builder.build()));
        ProtocolAccountRef account = new ProtocolAccountRef(
                1L, ProtocolBackend.WEB, "acc_861001", "861001");

        server.expect(requestTo("http://web.internal/v1/accounts/acc_861001/status"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"accountId":"acc_861001","state":"ONLINE"}
                        """, MediaType.APPLICATION_JSON));

        ProtocolAccountRuntimeStatus result = adapter.status(account);

        assertThat(result.state()).isEqualTo("ONLINE");
        assertThat(result.online()).isTrue();
        server.verify();
    }

    @Test
    void preservesHttpMetadataAndAddsCanonicalContextWhenStatusQueryFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://web.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebAccountRuntimeStatusAdapter adapter = new WebAccountRuntimeStatusAdapter(
                new ProtocolHttpExecutor(builder.build()));
        ProtocolAccountRef account = new ProtocolAccountRef(
                1L, ProtocolBackend.WEB, "acc_861001", "861001");

        server.expect(requestTo("http://web.internal/v1/accounts/acc_861001/status"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"ACCOUNT_NOT_FOUND","message":"account missing"}
                                """));

        assertThatThrownBy(() -> adapter.status(account))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.ACCOUNT_NOT_FOUND);
                    assertThat(ex.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
                    assertThat(ex.protocolCode()).contains("ACCOUNT_NOT_FOUND");
                    assertThat(ex.backend()).contains(ProtocolBackend.WEB);
                    assertThat(ex.operation()).contains("account.status");
                    assertThat(ex.operationId()).contains("account:1");
                });
        server.verify();
    }
}
