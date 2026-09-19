package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AndroidNativeGroupApprovalAdapterTest {
    private final RestClient.Builder builder=RestClient.builder().baseUrl("http://android.internal");
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private final AndroidNativeGroupApprovalAdapter port=new AndroidNativeGroupApprovalAdapter(
            new HttpAndroidNativeClient(new ProtocolHttpExecutor(builder.build())),new AndroidResponseDecoder(),new AndroidGroupOperationErrorMapper());
    private final ProtocolAccountRef account=new ProtocolAccountRef(1L,ProtocolBackend.ANDROID,"old","222");

    @Test
    void previewIsReadOnlyAndApprovalContainsOnlySpecifiedMember() {
        server.expect(requestTo("http://android.internal/ws/v1/groups/preview/222")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"Code\":\"abc\"}"))
                .andRespond(withSuccess("{\"Code\":0,\"Data\":{\"groupJid\":\"120@g.us\"}}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://android.internal/ws/v1/groups/members/pending/222"))
                .andExpect(content().json("{\"group_id\":\"120@g.us\"}"))
                .andRespond(withSuccess("{\"Code\":0,\"Data\":[{\"Jid\":\"111@s.whatsapp.net\"}]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://android.internal/ws/v1/groups/members/approve/222"))
                .andExpect(content().json("{\"group_id\":\"120@g.us\",\"participants\":[\"111@s.whatsapp.net\"],\"state\":true}"))
                .andRespond(withSuccess("{\"Code\":0,\"Data\":\"\"}",MediaType.APPLICATION_JSON));
        assertEquals("120@g.us",port.resolveGroup(account,"https://chat.whatsapp.com/abc"));
        assertEquals(List.of("111@s.whatsapp.net"),port.pending(account,"120@g.us"));
        port.approve(account,"120@g.us","111@s.whatsapp.net");server.verify();
    }
    @Test
    void http200BusinessFailureAndMissingPendingListAreNotSuccess() {
        server.expect(anything()).andRespond(withSuccess("{\"Code\":1003,\"Msg\":\"forbidden, Code: 403\"}",MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"Code\":0,\"Data\":null}",MediaType.APPLICATION_JSON));
        var failure=assertThrows(ProtocolException.class,()->port.approve(account,"120@g.us","111@s.whatsapp.net"));
        assertEquals("GROUP_PERMISSION_DENIED",failure.errorCode().name());
        assertThrows(ProtocolException.class,()->port.pending(account,"120@g.us"));server.verify();
    }
    @Test
    void explicitGroupAbnormalityIsPreservedForClearFailureReceipt() {
        server.expect(anything()).andRespond(withSuccess("{\"Code\":1003,\"Msg\":\"chat_suspended\"}",MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"Code\":1003,\"Msg\":\"group not found\"}",MediaType.APPLICATION_JSON));
        assertEquals("GROUP_BANNED",assertThrows(ProtocolException.class,
                ()->port.pending(account,"120@g.us")).errorCode().name());
        assertEquals("GROUP_UNAVAILABLE",assertThrows(ProtocolException.class,
                ()->port.pending(account,"120@g.us")).errorCode().name());
        server.verify();
    }
}
