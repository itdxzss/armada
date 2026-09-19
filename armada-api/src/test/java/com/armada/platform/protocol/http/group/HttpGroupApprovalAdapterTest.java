package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpGroupApprovalAdapterTest {
    private final RestClient.Builder builder=RestClient.builder().baseUrl("http://web.internal");
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private final HttpGroupApprovalAdapter port=new HttpGroupApprovalAdapter(new ProtocolHttpExecutor(builder.build()));
    private final ProtocolAccountRef account=new ProtocolAccountRef(1L,ProtocolBackend.WEB,"old","222");

    @Test
    void existingWebEndpointsPreserveRoutingAndValidateSingleTargetReceipt() {
        server.expect(requestTo("http://web.internal/v1/groups/preview"))
                .andExpect(content().json("{\"accountId\":\"old\",\"inviteLink\":\"https://chat.whatsapp.com/abc\"}"))
                .andRespond(withSuccess("{\"groupJid\":\"120@g.us\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://web.internal/v1/groups/120%40g.us/pending?accountId=old"))
                .andRespond(withSuccess("{\"pending\":[{\"jid\":\"111@s.whatsapp.net\"}]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://web.internal/v1/groups/120@g.us/pending/approve"))
                .andExpect(content().json("{\"accountId\":\"old\",\"participants\":[\"111@s.whatsapp.net\"],\"timeoutMs\":30000}"))
                .andRespond(withSuccess("{\"partial\":false,\"results\":[{\"jid\":\"111@s.whatsapp.net\",\"status\":\"OK\"}]}",MediaType.APPLICATION_JSON));
        assertEquals("120@g.us",port.resolveGroup(account,"https://chat.whatsapp.com/abc"));
        assertEquals(List.of("111@s.whatsapp.net"),port.pending(account,"120@g.us"));
        port.approve(account,"120@g.us","111@s.whatsapp.net");server.verify();
    }
    @Test
    void absentListAndWrongTargetReceiptCannotBeAccepted() {
        server.expect(anything()).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"partial\":false,\"results\":[{\"jid\":\"999@s.whatsapp.net\",\"status\":\"OK\"}]}",MediaType.APPLICATION_JSON));
        assertThrows(ProtocolException.class,()->port.pending(account,"120@g.us"));
        assertThrows(ProtocolException.class,()->port.approve(account,"120@g.us","111@s.whatsapp.net"));server.verify();
    }
}
