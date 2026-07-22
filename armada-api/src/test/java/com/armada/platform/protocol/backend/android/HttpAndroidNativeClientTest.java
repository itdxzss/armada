package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpAndroidNativeClientTest {

    @Test
    void sendsExistingAndroidNativeRequestShapes() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://android.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AndroidNativeClient client = new HttpAndroidNativeClient(
                new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://android.internal/ws/v1/auth/status/919000000001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"Code\":0,\"Data\":\"online\",\"Msg\":\"\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://android.internal/ws/v1/groups/invite/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"Code\":\"ABC123\"}"))
                .andRespond(withSuccess(
                        "{\"Code\":0,\"Data\":\"通过邀请码进群成功, 群聊ID: 120363001\",\"Msg\":\"\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://android.internal/ws/v1/groups/members/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"group_id\":\"120363001@g.us\"}"))
                .andRespond(withSuccess(
                        "{\"Code\":0,\"Data\":{\"Participants\":[]},\"Msg\":\"ok\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://android.internal/ws/v1/contacts/add/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"Numbers\":[\"919000000002\"]}"))
                .andRespond(withSuccess(
                        "{\"Code\":0,\"Data\":[],\"Msg\":\"\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://android.internal/ws/v1/groups/create/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"subject":"活动群-1","participants":["919000000002@s.whatsapp.net"]}
                        """))
                .andRespond(withSuccess("""
                        {"Code":0,"Data":{"GroupId":"120363001","Participants":[]},"Msg":""}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "http://android.internal/ws/v1/groups/settings/sendmessage/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"group_id":"120363001@g.us","state":false}
                        """))
                .andRespond(withSuccess(
                        "{\"Code\":0,\"Data\":\"\",\"Msg\":\"\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.status("919000000001").code()).isZero();
        assertThat(client.join("919000000001", "ABC123").code()).isZero();
        assertThat(client.members("919000000001", "120363001@g.us").code()).isZero();
        assertThat(client.saveContacts(
                "919000000001", List.of("919000000002")).code()).isZero();
        assertThat(client.createGroup(
                "919000000001",
                "活动群-1",
                List.of("919000000002@s.whatsapp.net")).code()).isZero();
        assertThat(client.setGroupAnnouncement(
                "919000000001", "120363001@g.us", false).code()).isZero();
        server.verify();
    }

    @Test
    void rejectsNonNumericAndroidPhoneBeforeSendingRequest() {
        AndroidNativeClient client = clientWithoutServer();

        assertThatThrownBy(() -> client.status("+919000000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("纯数字");
        assertThatThrownBy(() -> client.saveContacts(
                "acc_919000000001", List.of("919000000002")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("纯数字");
    }

    @Test
    void rejectsBlankAndroidRequestFieldsBeforeSendingRequest() {
        AndroidNativeClient client = clientWithoutServer();

        assertThatThrownBy(() -> client.join("919000000001", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inviteCode");
        assertThatThrownBy(() -> client.members("919000000001", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupJid");
        assertThatThrownBy(() -> client.saveContacts("919000000001", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numbers");
        assertThatThrownBy(() -> client.saveContacts("919000000001", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numbers item");
        assertThatThrownBy(() -> client.createGroup("919000000001", "活动群", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("participants");
        assertThatThrownBy(() -> client.setGroupAnnouncement("919000000001", " ", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupJid");
    }

    private static AndroidNativeClient clientWithoutServer() {
        return new HttpAndroidNativeClient(new ProtocolHttpExecutor(
                RestClient.builder().baseUrl("http://android.internal").build()));
    }
}
