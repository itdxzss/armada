package com.armada.platform.protocol.http.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.port.GroupMetadataPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpGroupMetadataAdapterTest {

    @Test
    void getMetadataMapsStableGroupDetailAndParticipantIdentity() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupMetadataPort port = new HttpGroupMetadataAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/120363detail@g.us/metadata?accountId=acc_7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "id": "120363detail@g.us",
                          "subject": "真实群名",
                          "announce": false,
                          "restrict": true,
                          "memberAddMode": true,
                          "joinApprovalMode": true,
                          "ephemeralDuration": 604800,
                          "inviteViaLink": null,
                          "capabilities": {
                            "inviteViaLink": {
                              "supported": false,
                              "reason": "Baileys 当前不支持"
                            }
                          },
                          "participants": [
                            {
                              "id": "8613800000000:7@s.whatsapp.net",
                              "phoneNumber": "8613800000000@s.whatsapp.net",
                              "lid": "123456789@lid",
                              "admin": "superadmin"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        GroupMetadataResult result = port.getMetadata("acc_7", "120363detail@g.us");

        assertThat(result.groupJid()).isEqualTo("120363detail@g.us");
        assertThat(result.subject()).isEqualTo("真实群名");
        assertThat(result.memberAddMode()).isTrue();
        assertThat(result.joinApprovalMode()).isTrue();
        assertThat(result.ephemeralDurationSeconds()).isEqualTo(604800);
        assertThat(result.inviteViaLink()).isNull();
        assertThat(result.inviteViaLinkSupported()).isFalse();
        assertThat(result.inviteViaLinkUnsupportedReason()).isEqualTo("Baileys 当前不支持");
        assertThat(result.participants()).hasSize(1);
        assertThat(result.participants().get(0).jid()).isEqualTo("8613800000000:7@s.whatsapp.net");
        assertThat(result.participants().get(0).phone()).isEqualTo("8613800000000");
        assertThat(result.participants().get(0).owner()).isTrue();
        server.verify();
    }
}
