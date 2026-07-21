package com.armada.platform.protocol.http.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.routing.GroupCreateBackend;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpGroupCreateAdapterTest {

    @Test
    void createPostsNormalizedParticipantsAndMapsResult() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupCreateBackend backend = new HttpGroupCreateAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_861111",
                          "subject": "测试群",
                          "participants": [
                            "8613900000000@s.whatsapp.net",
                            "8613911111111@s.whatsapp.net"
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363create@g.us",
                          "results": {
                            "groupJid": "120363create@g.us",
                            "partial": false,
                            "results": [
                              {
                                "jid": "8613900000000@s.whatsapp.net",
                                "status": "OK",
                                "rawStatus": "200"
                              },
                              {
                                "jid": "8613911111111@s.whatsapp.net",
                                "status": "PRIVACY_BLOCKED",
                                "rawStatus": "403"
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        GroupCreateResult result = backend.create(new GroupCreateCommand(
                new ProtocolAccountRef(7L, ProtocolBackend.WEB, "acc_861111", "861111"),
                " 测试群 ",
                List.of("+86 139-0000-0000", "8613911111111@s.whatsapp.net"),
                false,
                "test:web-group-create"));

        assertThat(result.groupJid()).isEqualTo("120363create@g.us");
        assertThat(result.partial()).isFalse();
        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(0).jid()).isEqualTo("8613900000000@s.whatsapp.net");
        assertThat(result.results().get(0).status()).isEqualTo("OK");
        assertThat(result.results().get(0).rawStatus()).isEqualTo("200");
        assertThat(result.results().get(1).status()).isEqualTo("PRIVACY_BLOCKED");
        server.verify();
    }

    @Test
    void createWithAnnounceOnlyPostsAnnouncementFlag() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupCreateBackend backend = new HttpGroupCreateAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_861111",
                          "subject": "测试群",
                          "participants": [
                            "8613900000000@s.whatsapp.net"
                          ],
                          "announceOnly": true
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363create@g.us",
                          "results": {
                            "groupJid": "120363create@g.us",
                            "partial": false,
                            "results": []
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        GroupCreateResult result = backend.create(new GroupCreateCommand(
                new ProtocolAccountRef(7L, ProtocolBackend.WEB, "acc_861111", "861111"),
                "测试群",
                List.of("8613900000000"),
                true,
                "test:web-group-create-announcement"));

        assertThat(result.groupJid()).isEqualTo("120363create@g.us");
        server.verify();
    }
}
