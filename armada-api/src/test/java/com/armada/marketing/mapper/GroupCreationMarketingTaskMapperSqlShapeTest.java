package com.armada.marketing.mapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupCreationMarketingTaskMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/GroupCreationMarketingTaskMapper.xml";

    @Test
    void accountCandidatesReadCurrentProtocolRoutingFact() throws IOException {
        String xml = mapperXml();

        for (String statementId : List.of(
                "selectAccountCandidatesByGroupId",
                "selectFirstAvailableAccountCandidateByGroupId",
                "selectFirstAvailableAccountCandidateByGroupIdExcluding",
                "selectAccountCandidateByAccountId")) {
            assertThat(selectBlock(xml, statementId))
                    .as(statementId)
                    .contains("a.protocol_id AS protocolId");
        }
    }

    @Test
    void accountRetryMapperStatementsKeepItemRetryScopedToOneItem() throws IOException {
        String xml = mapperXml();

        String candidateSql = selectBlock(xml, "selectFirstAvailableAccountCandidateByGroupIdExcluding");
        String resetSql = updateBlock(xml, "resetItemForAccountRetry");
        String claimRetrySql = updateBlock(xml, "updateItemAccountForClaimRetry");
        String noAvailableSql = updateBlock(xml, "markItemNoAvailableAccount");

        assertThat(candidateSql)
                .contains("a.account_group_id = #{accountGroupId}")
                .contains("a.id NOT IN")
                .contains("collection=\"excludedAccountIds\"")
                .contains("LIMIT 1");
        assertThat(resetSql)
                .contains("status = #{update.pendingStatus}")
                .contains("next_run_at = #{update.nextRunAt}")
                .contains("retry_history_json = #{update.retryHistoryJson}")
                .contains("marketing_attempt_id = NULL")
                .contains("command_id = NULL")
                .contains("WHERE id = #{update.id}")
                .contains("AND status = #{update.fromStatus}")
                .contains("AND command_id = #{update.expectedCommandId}");
        assertThat(claimRetrySql)
                .contains("account_id = #{update.accountId}")
                .contains("retry_history_json = #{update.retryHistoryJson}")
                .contains("WHERE id = #{update.id}")
                .contains("AND status = 2")
                .doesNotContain("status = #{update.pendingStatus}");
        assertThat(noAvailableSql)
                .contains("i.status = 6")
                .contains("i.reason_code = #{update.reasonCode}")
                .contains("i.retry_history_json = #{update.retryHistoryJson}")
                .contains("t.abandoned_count = t.abandoned_count + 1")
                .contains("WHERE i.id = #{update.id}")
                .contains("AND i.status = #{update.fromStatus}")
                .contains("AND i.command_id = #{update.expectedCommandId}");
    }

    private String mapperXml() throws IOException {
        return new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static String selectBlock(String xml, String id) {
        String startTag = "<select id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).as("mapper select " + id + " exists").isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</select>", start);
        assertThat(end).as("mapper select " + id + " closes").isGreaterThan(start);
        return xml.substring(start, end);
    }

    private static String updateBlock(String xml, String id) {
        String startTag = "<update id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).as("mapper update " + id + " exists").isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</update>", start);
        assertThat(end).as("mapper update " + id + " closes").isGreaterThan(start);
        return xml.substring(start, end);
    }
}
