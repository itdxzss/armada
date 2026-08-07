package com.armada.platform.protocol.backend.android;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupSettingsPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AndroidNativeGroupSettingsAdapterTest {

    @Test
    void enablesAllMembersToAddThroughAndroidNativeContract() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://android.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupSettingsPort port = new AndroidNativeGroupSettingsAdapter(
                new HttpAndroidNativeClient(new ProtocolHttpExecutor(builder.build())),
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper());

        server.expect(requestTo(
                        "http://android.internal/ws/v1/groups/settings/join-mode/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"group_id":"120363001@g.us","state":true}
                        """))
                .andRespond(withSuccess(
                        "{\"Code\":0,\"Data\":\"\",\"Msg\":\"\"}",
                        MediaType.APPLICATION_JSON));

        port.setAddMembersAllowed(account(), "120363001@g.us", true);

        server.verify();
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                7L,
                ProtocolBackend.ANDROID,
                "android_7",
                "919000000001");
    }
}
