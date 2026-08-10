package com.armada.platform.protocol.backend.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupPictureResult;
import com.armada.platform.protocol.port.GroupProfilePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AndroidNativeGroupProfileAdapterTest {

    @Test
    void updatesNameAndBase64PictureThroughAndroidContracts() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://android.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupProfilePort port = adapter(builder);

        server.expect(requestTo(
                        "http://android.internal/ws/v1/groups/settings/name/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"group_id":"120363001@g.us","name":"新群名"}
                        """))
                .andRespond(withSuccess("{\"Code\":0,\"Data\":\"\",\"Msg\":\"\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "http://android.internal/ws/v1/groups/settings/avatar/919000000001"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"group_id":"120363001@g.us","image_base64":"aW1hZ2U="}
                        """))
                .andRespond(withSuccess("{\"Code\":0,\"Data\":\"\",\"Msg\":\"\"}",
                        MediaType.APPLICATION_JSON));

        port.updateSubject(account(), "120363001@g.us", "新群名");
        GroupPictureResult result = port.updatePicture(
                account(), "120363001@g.us", null, "aW1hZ2U=");

        assertThat(result).isEqualTo(new GroupPictureResult(true, null));
        server.verify();
    }

    @Test
    void rejectsPictureUrlWithoutFallingBackToWeb() {
        GroupProfilePort port = adapter(RestClient.builder().baseUrl("http://android.internal"));

        assertThatThrownBy(() -> port.updatePicture(
                account(), "120363001@g.us", "https://cdn.test/a.jpg", null))
                .isInstanceOfSatisfying(ProtocolException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(
                                ProtocolErrorCode.GROUP_CAPABILITY_UNSUPPORTED));
    }

    private static GroupProfilePort adapter(RestClient.Builder builder) {
        return new AndroidNativeGroupProfileAdapter(
                new HttpAndroidNativeClient(new ProtocolHttpExecutor(builder.build())),
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper());
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                7L, ProtocolBackend.ANDROID, "android_7", "919000000001");
    }
}
