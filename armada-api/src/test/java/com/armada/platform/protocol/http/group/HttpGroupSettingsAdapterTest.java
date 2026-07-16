package com.armada.platform.protocol.http.group;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.port.GroupSettingsPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpGroupSettingsAdapterTest {

    @Test
    void setEphemeralDurationPostsWireMode() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupSettingsPort port = new HttpGroupSettingsAdapter(
                new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo(
                        "http://protocol-master.internal/v1/groups/120363settings@g.us/settings/ephemeral"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_7",
                          "mode": "7d"
                        }
                        """))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        port.setEphemeralDuration("acc_7", "120363settings@g.us", 604_800);

        server.verify();
    }

    @Test
    void setEphemeralDurationRejectsUnknownSecondsBeforeHttpCall() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupSettingsPort port = new HttpGroupSettingsAdapter(
                new ProtocolHttpExecutor(builder.build()));

        assertThatThrownBy(() -> port.setEphemeralDuration(
                "acc_7", "120363settings@g.us", 123))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("不支持的群限时消息秒数");

        server.verify();
    }

    @Test
    void stablePermissionMethodsPostTheirExplicitWireModes() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupSettingsPort port = new HttpGroupSettingsAdapter(
                new ProtocolHttpExecutor(builder.build()));

        expectMode(server, "locked", "unlocked");
        expectMode(server, "announcement", "announcement");
        expectMode(server, "member-add-mode", "all_member_add");
        expectMode(server, "join-approval", "on");

        port.setEditGroupSettingsAllowed("acc_7", "120363settings@g.us", true);
        port.setSendMessagesAllowed("acc_7", "120363settings@g.us", false);
        port.setAddMembersAllowed("acc_7", "120363settings@g.us", true);
        port.setJoinApprovalEnabled("acc_7", "120363settings@g.us", true);

        server.verify();
    }

    @Test
    void inviteViaLinkFailsExplicitlyWithoutCallingHttp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupSettingsPort port = new HttpGroupSettingsAdapter(
                new ProtocolHttpExecutor(builder.build()));

        assertThatThrownBy(() -> port.setInviteViaLinkAllowed(
                "acc_7", "120363settings@g.us", true))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("未暴露通过链接邀请权限");

        server.verify();
    }

    private static void expectMode(
            MockRestServiceServer server,
            String path,
            String mode) {
        server.expect(requestTo(
                        "http://protocol-master.internal/v1/groups/120363settings@g.us/settings/" + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_7",
                          "mode": "%s"
                        }
                        """.formatted(mode)))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
    }
}
