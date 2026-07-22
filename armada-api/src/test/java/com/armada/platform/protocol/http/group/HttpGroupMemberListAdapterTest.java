package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.routing.GroupMemberListBackend;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpGroupMemberListAdapterTest {

    @Test
    void listsWebGroupParticipantsAndMapsRoles() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupMemberListBackend backend = new HttpGroupMemberListAdapter(
                new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo(
                        "http://protocol-master.internal/v1/groups/120363created@g.us/participants?accountId=acc_7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          { "id": "8613800000000@s.whatsapp.net", "admin": "superadmin" },
                          { "id": "8613900000000:12@s.whatsapp.net", "admin": "admin" },
                          { "id": "8613700000000@s.whatsapp.net" }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<GroupParticipantResult> result = backend.list(new GroupMemberListQuery(
                new ProtocolAccountRef(7L, ProtocolBackend.WEB, "acc_7", "8613000000000"),
                "120363created@g.us",
                "group-creation-marketing-item:11"));

        assertThat(result).containsExactly(
                new GroupParticipantResult(
                        "8613800000000@s.whatsapp.net", "8613800000000", true, true, "superadmin"),
                new GroupParticipantResult(
                        "8613900000000:12@s.whatsapp.net", "8613900000000", true, false, "admin"),
                new GroupParticipantResult(
                        "8613700000000@s.whatsapp.net", "8613700000000", false, false, null));
        server.verify();
    }
}
