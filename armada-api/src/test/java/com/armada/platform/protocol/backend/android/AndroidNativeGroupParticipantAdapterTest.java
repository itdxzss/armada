package com.armada.platform.protocol.backend.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AndroidNativeGroupParticipantAdapterTest {

    @Test
    void promotesEachMemberThroughExistingAndroidAdminContract() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://android.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupParticipantPort port = new AndroidNativeGroupParticipantAdapter(
                new HttpAndroidNativeClient(new ProtocolHttpExecutor(builder.build())),
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper());

        for (String participant : List.of(
                "919000000002@s.whatsapp.net",
                "919000000003@s.whatsapp.net")) {
            server.expect(requestTo(
                            "http://android.internal/ws/v1/groups/admin/set/919000000001"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().json("""
                            {
                              "group_id":"120363001@g.us",
                              "state":true,
                              "participant":"%s"
                            }
                            """.formatted(participant)))
                    .andRespond(withSuccess(
                            "{\"Code\":0,\"Data\":\"设置管理员成功\",\"Msg\":\"\"}",
                            MediaType.APPLICATION_JSON));
        }

        GroupParticipantBatchResult result = port.updateParticipants(
                account(),
                "120363001@g.us",
                List.of(
                        "919000000002@s.whatsapp.net",
                        "919000000003@s.whatsapp.net"),
                GroupParticipantAction.PROMOTE);

        assertThat(result.partial()).isFalse();
        assertThat(result.results()).extracting(GroupParticipantBatchResult.Item::status)
                .containsExactly("OK", "OK");
        server.verify();
    }

    @Test
    void removesEachMemberThroughAndroidNativeContractAndKeepsFailures() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://android.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupParticipantPort port = new AndroidNativeGroupParticipantAdapter(
                new HttpAndroidNativeClient(new ProtocolHttpExecutor(builder.build())),
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper());

        expectRemove(server, "919000000002@s.whatsapp.net", """
                {"Code":0,"Data":"删除群成员成功","Msg":""}
                """);
        expectRemove(server, "919000000003@s.whatsapp.net", """
                {"Code":1004,"Data":"删除群成员失败, not-authorized, Code: 403","Msg":""}
                """);

        GroupParticipantBatchResult result = port.updateParticipants(
                account(),
                "120363001@g.us",
                List.of(
                        "919000000002@s.whatsapp.net",
                        "919000000003@s.whatsapp.net"),
                GroupParticipantAction.REMOVE);

        assertThat(result.partial()).isTrue();
        assertThat(result.results()).containsExactly(
                new GroupParticipantBatchResult.Item(
                        "919000000002@s.whatsapp.net", "OK", null),
                new GroupParticipantBatchResult.Item(
                        "919000000003@s.whatsapp.net",
                        "FAILED",
                        "GROUP_PERMISSION_DENIED"));
        server.verify();
    }

    private static void expectRemove(
            MockRestServiceServer server,
            String participant,
            String responseBody) {
        server.expect(requestTo(
                        "http://android.internal/ws/v1/groups/members/remove/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "group_id":"120363001@g.us",
                          "participant":"%s"
                        }
                        """.formatted(participant)))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                7L,
                ProtocolBackend.ANDROID,
                "android_7",
                "919000000001");
    }
}
