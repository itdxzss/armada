package com.armada.marketing.mapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingTaskMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/MarketingTaskMapper.xml";

    @Test
    void marketingAccountSelectionUsesSnapshotForFixedTargetsAndMembershipForDynamicTargets() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String candidateSql = selectBlock(xml, "selectTargetCandidate");
        String dynamicTargetSql = selectBlock(xml, "selectDynamicTargetGroups");
        String currentTargetSql = selectBlock(xml, "selectCurrentTargetGroup");
        String treeAccountSql = selectBlock(xml, "selectAccountTreeAccounts");

        assertThat(candidateSql)
                .contains("JOIN group_link_preview p ON p.group_link_id = g.id")
                .contains("p.group_jid AS groupJid")
                .contains("a.account_group_id = #{accountGroupId}");
        assertThat(candidateSql).doesNotContain("account_group_membership m");
        assertThat(dynamicTargetSql)
                .contains("JOIN account_group_membership m")
                .contains("m.group_jid AS groupJid")
                .contains("(#{accountGroupSendAt} IS NULL OR m.joined_at &gt;= #{accountGroupSendAt})");
        assertThat(currentTargetSql)
                .contains("JOIN account_group_membership m")
                .contains("m.group_link_id = #{groupLinkId}")
                .contains("WHERE a.id = #{accountId}");
        assertThat(treeAccountSql).contains("a.protocol_account_id AS protocolAccountId");
        assertThat(treeAccountSql)
                .contains("account_group_membership")
                .contains("COALESCE(gm.groupCount, 0) AS groupCount")
                .contains("s.login_state AS loginState");
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

    @Test
    void taskActivationUsesExpectedStatusAndDoesNotRewriteSchedule() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = updateBlock(xml, "activateTask");

        assertThat(sql)
                .contains("status = #{nextStatus}")
                .contains("status = #{expectedStatus}")
                .contains("task_end_at &gt; #{now}")
                .contains("CASE WHEN #{nextStatus} = 2")
                .doesNotContain("task_start_at =")
                .doesNotContain("task_end_at =")
                .doesNotContain("account_group_send_at =");
    }

    @Test
    void earlySendingTaskReturnsToWaitingWithSqlGuard() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = updateBlock(xml, "deferEarlySendingTask");

        assertThat(sql)
                .contains("SET status = 1")
                .contains("next_round_at = NULL")
                .contains("status = 2")
                .contains("task_start_at &gt; #{now}");
    }

    @Test
    void endedTaskRestartRewritesOnlyLifecycleWindow() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = updateBlock(xml, "restartEndedTask");

        assertThat(sql)
                .contains("status = #{nextStatus}")
                .contains("task_start_at = #{taskStartAt}")
                .contains("task_end_at = #{taskEndAt}")
                .contains("finished_at = NULL")
                .contains("status = 7")
                .contains("#{taskEndAt} &gt; #{now}")
                .doesNotContain("account_group_send_at =")
                .doesNotContain("sent_message_count =")
                .doesNotContain("failed_message_count =")
                .doesNotContain("current_round_no =");
    }

    @Test
    void stoppedTaskIsArchivedWhenItsPlanExpires() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String selectSql = selectBlock(xml, "selectExpiredRunnableTasks");
        String updateSql = updateBlock(xml, "endExpiredTask");

        assertThat(selectSql).contains("status IN (1, 2, 5)");
        assertThat(updateSql).contains("status IN (1, 2, 5)");
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
