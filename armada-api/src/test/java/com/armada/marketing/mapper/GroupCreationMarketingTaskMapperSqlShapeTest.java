package com.armada.marketing.mapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupCreationMarketingTaskMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/GroupCreationMarketingTaskMapper.xml";

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
                .contains("status = #{pendingStatus}")
                .contains("next_run_at = #{nextRunAt}")
                .contains("retry_history_json = #{retryHistoryJson}")
                .contains("marketing_attempt_id = NULL")
                .contains("command_id = NULL")
                .contains("WHERE id = #{id}")
                .contains("AND status = #{fromStatus}")
                .contains("AND command_id = #{expectedCommandId}");
        assertThat(claimRetrySql)
                .contains("account_id = #{accountId}")
                .contains("retry_history_json = #{retryHistoryJson}")
                .contains("WHERE id = #{id}")
                .contains("AND status = 2")
                .doesNotContain("status = #{pendingStatus}");
        assertThat(noAvailableSql)
                .contains("i.status = 6")
                .contains("i.reason_code = #{reasonCode}")
                .contains("i.retry_history_json = #{retryHistoryJson}")
                .contains("t.abandoned_count = t.abandoned_count + 1")
                .contains("WHERE i.id = #{id}")
                .contains("AND i.status = #{fromStatus}")
                .contains("AND i.command_id = #{expectedCommandId}");
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
