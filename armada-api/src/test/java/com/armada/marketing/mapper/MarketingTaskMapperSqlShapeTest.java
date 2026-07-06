package com.armada.marketing.mapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingTaskMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/MarketingTaskMapper.xml";

    @Test
    void marketingAccountSelectionUsesRealtimeProtocolAndFixedSaveDoesNotRequireMembership() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String candidateSql = selectBlock(xml, "selectTargetCandidate");
        String treeAccountSql = selectBlock(xml, "selectAccountTreeAccounts");

        assertThat(candidateSql).contains("p.group_jid AS groupJid");
        assertThat(candidateSql)
                .doesNotContain("JOIN account_group_membership")
                .doesNotContain("m.group_jid");
        assertThat(treeAccountSql).contains("a.protocol_account_id AS protocolAccountId");
        assertThat(treeAccountSql)
                .doesNotContain("account_group_membership")
                .doesNotContain("group_link_preview p");
    }

    @Test
    void sendResultRollupBackfillsAttemptJidAndTargetGroupSnapshot() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String markAttemptSuccessSql = updateBlock(xml, "markAttemptSuccess");
        String markAttemptFailedSql = updateBlock(xml, "markAttemptFailed");
        String markTargetSuccessSql = updateBlock(xml, "markTargetSuccessFromAttempt");
        String markTargetFailedSql = updateBlock(xml, "markTargetFailedFromAttempt");

        assertThat(markAttemptSuccessSql)
                .contains("group_jid = COALESCE(NULLIF(TRIM(group_jid), ''), NULLIF(TRIM(#{groupJid}), ''))");
        assertThat(markAttemptFailedSql)
                .contains("group_jid = COALESCE(NULLIF(TRIM(group_jid), ''), NULLIF(TRIM(#{groupJid}), ''))");
        assertThat(markTargetSuccessSql)
                .contains("LEFT JOIN group_link_preview p")
                .contains("LEFT JOIN group_link g ON g.id = COALESCE(a.group_link_id, p.group_link_id, t.group_link_id)")
                .contains("COALESCE(a.group_link_id, p.group_link_id, t.group_link_id)")
                .contains("t.group_link_url = COALESCE(g.link_url, t.group_link_url)")
                .contains("COALESCE(NULLIF(TRIM(a.group_name), ''), NULLIF(TRIM(g.group_name), ''), p.wa_subject, t.group_name)");
        assertThat(markTargetFailedSql)
                .contains("LEFT JOIN group_link_preview p")
                .contains("LEFT JOIN group_link g ON g.id = COALESCE(a.group_link_id, p.group_link_id, t.group_link_id)")
                .contains("COALESCE(a.group_link_id, p.group_link_id, t.group_link_id)")
                .contains("t.group_link_url = COALESCE(g.link_url, t.group_link_url)")
                .contains("COALESCE(NULLIF(TRIM(a.group_name), ''), NULLIF(TRIM(g.group_name), ''), p.wa_subject, t.group_name)");
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
