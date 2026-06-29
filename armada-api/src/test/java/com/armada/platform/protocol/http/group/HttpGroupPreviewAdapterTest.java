package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.GroupPreviewResult;
import com.armada.platform.protocol.port.GroupPreviewPort;
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

class HttpGroupPreviewAdapterTest {

    @Test
    void previewPostsAccountAndInviteLinkToMasterAndMapsResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupPreviewPort port = new HttpGroupPreviewAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/preview"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_861111",
                          "inviteLink": "https://chat.whatsapp.com/ABC123"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363preview@g.us",
                          "subject": "预览群",
                          "memberCount": 12,
                          "size": 12,
                          "isBanned": false,
                          "ownerJid": "8613999999999@s.whatsapp.net",
                          "desc": "hello",
                          "announce": true,
                          "restrict": false,
                          "inviteCode": "ABC123",
                          "previewAt": "2026-06-02T10:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        GroupPreviewResult result = port.preview("acc_861111", "https://chat.whatsapp.com/ABC123");

        assertThat(result.groupJid()).isEqualTo("120363preview@g.us");
        assertThat(result.subject()).isEqualTo("预览群");
        assertThat(result.memberCount()).isEqualTo(12);
        assertThat(result.banned()).isFalse();
        assertThat(result.ownerJid()).isEqualTo("8613999999999@s.whatsapp.net");
        assertThat(result.announce()).isTrue();
        assertThat(result.inviteCode()).isEqualTo("ABC123");
        assertThat(result.previewAt()).isNotNull();
        server.verify();
    }
}
