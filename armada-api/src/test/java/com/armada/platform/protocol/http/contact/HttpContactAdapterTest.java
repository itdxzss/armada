package com.armada.platform.protocol.http.contact;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.routing.ContactBackend;
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
        ContactBackend backend = new HttpContactAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/contacts/8613900000000@s.whatsapp.net/save"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_7",
                          "contact": {
                            "fullName": "8613900000000",
                            "saveOnPrimaryAddressbook": true
                          }
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "ok": true
                        }
                        """, MediaType.APPLICATION_JSON));

        backend.save(new ContactSaveCommand(
                new ProtocolAccountRef(7L, ProtocolBackend.WEB, "acc_7", "8613000000000"),
                "+86 139-0000-0000",
                "8613900000000",
                "test:web-contact-save"));

        server.verify();
    }
}
