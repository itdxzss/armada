package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpGroupJoinAdapterTest {

    @Test
    void joinWithInviteLinkPostsInviteLinkAndMapsJoinedResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupJoinPort port = new HttpGroupJoinAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol.internal/v1/groups/join"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_861111",
                          "inviteLink": "https://chat.whatsapp.com/ABC123"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363join@g.us",
                          "joined": true
                        }
                        """, MediaType.APPLICATION_JSON));

        GroupJoinResult result = port.join("acc_861111", "https://chat.whatsapp.com/ABC123");

        assertThat(result.groupJid()).isEqualTo("120363join@g.us");
        assertThat(result.joined()).isTrue();
        server.verify();
    }

    @Test
    void joinWithInviteCodePostsInviteCodeAndPreservesPendingApproval() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupJoinPort port = new HttpGroupJoinAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol.internal/v1/groups/join"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_862222",
                          "inviteCode": "CODE456"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363pending@g.us",
                          "joined": false
                        }
                        """, MediaType.APPLICATION_JSON));

        GroupJoinResult result = port.join("acc_862222", "CODE456");

        assertThat(result.groupJid()).isEqualTo("120363pending@g.us");
        assertThat(result.joined()).isFalse();
        server.verify();
    }
}
