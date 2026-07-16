package com.armada.platform.protocol.http.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.port.GroupParticipantPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpGroupParticipantAdapterTest {

    @Test
    void listParticipantsGetsGroupParticipantsAndMapsRoles() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupParticipantPort port = new HttpGroupParticipantAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/120363members@g.us/participants?accountId=acc_861111"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          { "id": "8613800000000@s.whatsapp.net", "admin": "superadmin" },
                          { "id": "8613900000000@s.whatsapp.net", "admin": "admin" },
                          { "id": "8613700000000@s.whatsapp.net" }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<GroupParticipantResult> result = port.listParticipants("acc_861111", "120363members@g.us");

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(new GroupParticipantResult(
                "8613800000000@s.whatsapp.net", "8613800000000", true, true, "superadmin"));
        assertThat(result.get(1)).isEqualTo(new GroupParticipantResult(
                "8613900000000@s.whatsapp.net", "8613900000000", true, false, "admin"));
        assertThat(result.get(2)).isEqualTo(new GroupParticipantResult(
                "8613700000000@s.whatsapp.net", "8613700000000", false, false, null));
        server.verify();
    }

    @Test
    void updateParticipantsPostsEachSupportedMutationAndMapsPerJidResults() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupParticipantPort port = new HttpGroupParticipantAdapter(
                new ProtocolHttpExecutor(builder.build()));

        for (GroupParticipantAction action : GroupParticipantAction.values()) {
            server.expect(requestTo(
                            "http://protocol-master.internal/v1/groups/120363members@g.us/participants/"
                                    + action.wireValue()))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("""
                            {
                              "accountId": "acc_7",
                              "participants": ["8613800000000@s.whatsapp.net"],
                              "timeoutMs": 30000
                            }
                            """))
                    .andRespond(withSuccess("""
                            {
                              "groupJid": "120363members@g.us",
                              "partial": false,
                              "results": [{
                                "jid": "8613800000000@s.whatsapp.net",
                                "status": "OK",
                                "rawStatus": "200"
                              }]
                            }
                            """, MediaType.APPLICATION_JSON));
        }

        for (GroupParticipantAction action : GroupParticipantAction.values()) {

            GroupParticipantBatchResult result = port.updateParticipants(
                    "acc_7",
                    "120363members@g.us",
                    List.of("8613800000000@s.whatsapp.net"),
                    action);

            assertThat(result.partial()).isFalse();
            assertThat(result.results()).containsExactly(new GroupParticipantBatchResult.Item(
                    "8613800000000@s.whatsapp.net", "OK", "200"));
        }
        server.verify();
    }
}
