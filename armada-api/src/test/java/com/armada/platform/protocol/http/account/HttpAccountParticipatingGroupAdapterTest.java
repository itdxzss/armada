package com.armada.platform.protocol.http.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpAccountParticipatingGroupAdapterTest {

    @Test
    void listBatchPostsAccountIdsAndMapsPerAccountGroups() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountParticipatingGroupPort port =
                new HttpAccountParticipatingGroupAdapter(new ProtocolHttpExecutor(builder.build()));

        server.expect(requestTo("http://protocol-master.internal/v1/accounts/groups/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "accountIds": ["acc_861111", "acc_862222"],
                          "concurrency": 5
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "total": 2,
                          "succeeded": 1,
                          "failed": 1,
                          "results": [
                            {
                              "accountId": "acc_861111",
                              "success": true,
                              "groups": [
                                {
                                  "groupJid": "120363111@g.us",
                                  "subject": "新群",
                                  "size": 12,
                                  "owner": "8613000000000@s.whatsapp.net",
                                  "isAdmin": true,
                                  "announce": false,
                                  "creation": 1710000000
                                }
                              ]
                            },
                            {
                              "accountId": "acc_862222",
                              "success": false,
                              "error": "socket not found"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<AccountParticipatingGroupResult> results =
                port.listBatch(List.of("acc_861111", "acc_862222"), 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).protocolAccountId()).isEqualTo("acc_861111");
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).error()).isNull();
        assertThat(results.get(0).groups()).singleElement().satisfies(group -> {
            assertThat(group.groupJid()).isEqualTo("120363111@g.us");
            assertThat(group.subject()).isEqualTo("新群");
            assertThat(group.memberCount()).isEqualTo(12);
            assertThat(group.ownerJid()).isEqualTo("8613000000000@s.whatsapp.net");
            assertThat(group.admin()).isTrue();
            assertThat(group.announceOnly()).isFalse();
        });
        assertThat(results.get(1).protocolAccountId()).isEqualTo("acc_862222");
        assertThat(results.get(1).success()).isFalse();
        assertThat(results.get(1).groups()).isEmpty();
        assertThat(results.get(1).error()).isEqualTo("socket not found");
        server.verify();
    }

    @Test
    void listBatchSplitsRequestsByProtocolBatchLimit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AccountParticipatingGroupPort port =
                new HttpAccountParticipatingGroupAdapter(new ProtocolHttpExecutor(builder.build()));
        List<String> accountIds = IntStream.rangeClosed(1, 201)
                .mapToObj(index -> "acc_" + index)
                .toList();

        server.expect(requestTo("http://protocol-master.internal/v1/accounts/groups/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(batchRequestJson(accountIds.subList(0, 200), 5)))
                .andRespond(withSuccess(batchResponseJson(accountIds.subList(0, 200)), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://protocol-master.internal/v1/accounts/groups/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(batchRequestJson(accountIds.subList(200, 201), 5)))
                .andRespond(withSuccess(batchResponseJson(accountIds.subList(200, 201)), MediaType.APPLICATION_JSON));

        List<AccountParticipatingGroupResult> results = port.listBatch(accountIds, 5);

        assertThat(results)
                .extracting(AccountParticipatingGroupResult::protocolAccountId)
                .containsExactlyElementsOf(accountIds);
        server.verify();
    }

    private static String batchRequestJson(List<String> accountIds, int concurrency) {
        return """
                {
                  "accountIds": [%s],
                  "concurrency": %d
                }
                """.formatted(quotedCsv(accountIds), concurrency);
    }

    private static String batchResponseJson(List<String> accountIds) {
        return """
                {
                  "total": %d,
                  "succeeded": %d,
                  "failed": 0,
                  "results": [%s]
                }
                """.formatted(accountIds.size(), accountIds.size(), accountResultJson(accountIds));
    }

    private static String accountResultJson(List<String> accountIds) {
        return accountIds.stream()
                .map(accountId -> """
                        {
                          "accountId": "%s",
                          "success": true,
                          "groups": []
                        }
                        """.formatted(accountId))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static String quotedCsv(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
