package com.armada.platform.protocol.http.contact;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.port.ContactPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpContactAdapterTest {

    @Test
    void saveContactPostsNormalizedJidAndNestedContactBody() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ContactPort port = new HttpContactAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/contacts/8613900000000@s.whatsapp.net/save"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_7",
                          "contact": {
                            "name": "8613900000000"
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "ok": true
                        }
                        """, MediaType.APPLICATION_JSON));

        port.saveContact("acc_7", "+86 139-0000-0000", "8613900000000");

        server.verify();
    }
}
