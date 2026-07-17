package com.armada.platform.protocol.backend.web;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
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

class WebNativeGroupJoinAdapterTest {

    @Test
    void joinWithInviteLinkPostsInviteLinkAndMapsJoinedResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebNativeGroupJoinAdapter adapter = new WebNativeGroupJoinAdapter(
                new ProtocolHttpExecutor(builder.build()));

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

        GroupJoinResult result = adapter.join(new GroupJoinCommand(
                new ProtocolAccountRef(1L, ProtocolBackend.WEB, "acc_861111", "861111"),
                "https://chat.whatsapp.com/ABC123",
                "join-task-result:1"));

        assertThat(result.groupJid()).isEqualTo("120363join@g.us");
        assertThat(result.outcome()).isEqualTo(GroupJoinOutcome.JOINED);
        server.verify();
    }

    @Test
    void joinWithInviteCodePostsInviteCodeAndPreservesPendingApproval() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebNativeGroupJoinAdapter adapter = new WebNativeGroupJoinAdapter(
                new ProtocolHttpExecutor(builder.build()));

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

        GroupJoinResult result = adapter.join(new GroupJoinCommand(
                new ProtocolAccountRef(2L, ProtocolBackend.WEB, "acc_862222", "862222"),
                "CODE456",
                "join-task-result:2"));

        assertThat(result.groupJid()).isEqualTo("120363pending@g.us");
        assertThat(result.outcome()).isEqualTo(GroupJoinOutcome.PENDING_APPROVAL);
        server.verify();
    }

    @Test
    void joinRecognizesUppercaseHttpSchemeAsInviteLink() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WebNativeGroupJoinAdapter adapter = new WebNativeGroupJoinAdapter(
                new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol.internal/v1/groups/join"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_863333",
                          "inviteLink": "HTTPS://CHAT.WHATSAPP.COM/AbC789/"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363uppercase@g.us",
                          "joined": true
                        }
                        """, MediaType.APPLICATION_JSON));

        GroupJoinResult result = adapter.join(new GroupJoinCommand(
                new ProtocolAccountRef(3L, ProtocolBackend.WEB, "acc_863333", "863333"),
                "HTTPS://CHAT.WHATSAPP.COM/AbC789/",
                "join-task-result:3"));

        assertThat(result.joined()).isTrue();
        server.verify();
    }
}
