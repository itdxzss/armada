package com.armada.platform.protocol.http.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.GroupInvitePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpGroupInviteAdapterTest {

    @Test
    void getInviteEncodesPathAndQueryAndMapsInviteLink() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupInvitePort port = new HttpGroupInviteAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo(
                        "http://protocol-master.internal/v1/groups/120363test%40g.us/invite-code"
                                + "?accountId=acc_86%2F1111"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363test@g.us",
                          "inviteCode": "ABC123",
                          "inviteUrl": "https://chat.whatsapp.com/ABC123"
                        }
                        """, MediaType.APPLICATION_JSON));

        GroupInviteResult result = port.getInvite(account("acc_86/1111"), "120363test@g.us");

        assertThat(result).isEqualTo(new GroupInviteResult(
                "120363test@g.us", "ABC123", "https://chat.whatsapp.com/ABC123"));
        server.verify();
    }

    @Test
    void getInviteRejectsBlankInviteUrlInsteadOfReportingFalseSuccess() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupInvitePort port = new HttpGroupInviteAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo(
                        "http://protocol-master.internal/v1/groups/120363test%40g.us/invite-code"
                                + "?accountId=acc_861111"))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363test@g.us",
                          "inviteCode": "ABC123",
                          "inviteUrl": " "
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> port.getInvite(account("acc_861111"), "120363test@g.us"))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("inviteUrl");
        server.verify();
    }

    private static ProtocolAccountRef account(String protocolAccountId) {
        return new ProtocolAccountRef(1L, ProtocolBackend.WEB, protocolAccountId, "8613800000000");
    }
}
