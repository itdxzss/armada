package com.armada.platform.protocol.http.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.GroupPictureResult;
import com.armada.platform.protocol.port.GroupProfilePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpGroupProfileAdapterTest {

    @Test
    void updateSubjectPostsGroupSubject() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupProfilePort port = new HttpGroupProfileAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/120363profile@g.us/subject"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_7",
                          "subject": "新群名"
                        }
                        """))
                .andRespond(withSuccess("{\"success\":true,\"groupJid\":\"120363profile@g.us\"}",
                        MediaType.APPLICATION_JSON));

        port.updateSubject("acc_7", "120363profile@g.us", "新群名");

        server.verify();
    }

    @Test
    void updateDescriptionPostsGroupDescription() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupProfilePort port = new HttpGroupProfileAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/120363profile@g.us/description"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_7",
                          "description": "群描述"
                        }
                        """))
                .andRespond(withSuccess("{\"success\":true,\"groupJid\":\"120363profile@g.us\"}",
                        MediaType.APPLICATION_JSON));

        port.updateDescription("acc_7", "120363profile@g.us", "群描述");

        server.verify();
    }

    @Test
    void updateAnnouncementTextPostsAnnouncementText() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupProfilePort port = new HttpGroupProfileAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/120363profile@g.us/announcement-text"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_7",
                          "text": "群公告"
                        }
                        """))
                .andRespond(withSuccess("{\"success\":true,\"groupJid\":\"120363profile@g.us\"}",
                        MediaType.APPLICATION_JSON));

        port.updateAnnouncementText("acc_7", "120363profile@g.us", "群公告");

        server.verify();
    }

    @Test
    void updatePicturePostsNestedImageUrl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupProfilePort port = new HttpGroupProfileAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/120363profile@g.us/picture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_7",
                          "image": {
                            "url": "https://cdn.example.test/group.jpg"
                          }
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "groupJid": "120363profile@g.us",
                          "avatarUrl": "https://pps.whatsapp.net/new.jpg"
                        }
                        """, MediaType.APPLICATION_JSON));

        GroupPictureResult result = port.updatePicture(
                "acc_7", "120363profile@g.us", "https://cdn.example.test/group.jpg", null);

        assertThat(result).isEqualTo(new GroupPictureResult(true, "https://pps.whatsapp.net/new.jpg"));
        server.verify();
    }

    @Test
    void updatePicturePostsNestedImageBase64WithoutNullUrl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupProfilePort port = new HttpGroupProfileAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/120363profile@g.us/picture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountId": "acc_7",
                          "image": {
                            "base64": "aW1hZ2U="
                          }
                        }
                        """, true))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        port.updatePicture("acc_7", "120363profile@g.us", null, "aW1hZ2U=");

        server.verify();
    }

    @Test
    void getPictureUrlReturnsCurrentAvatarUrl() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupProfilePort port = new HttpGroupProfileAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/120363profile@g.us/picture?accountId=acc_7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363profile@g.us",
                          "avatarUrl": "https://pps.whatsapp.net/current.jpg"
                        }
                        """, MediaType.APPLICATION_JSON));

        String avatarUrl = port.getPictureUrl("acc_7", "120363profile@g.us");

        assertThat(avatarUrl).isEqualTo("https://pps.whatsapp.net/current.jpg");
        server.verify();
    }

    @Test
    void getPictureUrlKeepsProtocolNull() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroupProfilePort port = new HttpGroupProfileAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/groups/120363profile@g.us/picture?accountId=acc_7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "groupJid": "120363profile@g.us",
                          "avatarUrl": null
                        }
                        """, MediaType.APPLICATION_JSON));

        String avatarUrl = port.getPictureUrl("acc_7", "120363profile@g.us");

        assertThat(avatarUrl).isNull();
        server.verify();
    }
}
