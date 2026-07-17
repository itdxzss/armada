package com.armada.marketing.mapper;

import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingTaskMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/MarketingTaskMapper.xml";
    private static final Path FIVE_STATE_MIGRATION = Path.of(
            "src/main/resources/db/migration/V050__marketing_task_five_state_lifecycle.sql");
    private static final Path GROUP_STATUS_MIGRATION = Path.of(
            "src/main/resources/db/migration/V052__marketing_attempt_group_status.sql");

    @Test
    void executionTargetsReadCurrentProtocolRoutingFactsFromAccount() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("<result column=\"protocol_id\" property=\"protocolId\"/>")
                .contains("<result column=\"protocol_ws_phone\" property=\"protocolWsPhone\"/>")
                .contains("a.protocol_id AS protocol_id")
                .contains("a.ws_phone AS protocol_ws_phone");
    }

    @Test
    void taskLifecycleUsesFiveStatesAndForwardMigration() throws IOException {
        assertThat(Arrays.stream(MarketingTaskStatus.values()).map(Enum::name))
                .containsExactly("PENDING", "SENDING", "PAUSED", "COMPLETED", "CLOSED");
        Map<String, Integer> statusCodes = Arrays.stream(MarketingTaskStatus.values())
                .collect(Collectors.toMap(Enum::name, MarketingTaskStatus::code));
        assertThat(statusCodes)
                .containsEntry("PENDING", 1)
                .containsEntry("SENDING", 2)
                .containsEntry("PAUSED", 5)
                .containsEntry("COMPLETED", 7)
                .containsEntry("CLOSED", 8);

        assertThat(FIVE_STATE_MIGRATION).as("five-state migration exists").exists();
        String migrationSql = Files.readString(FIVE_STATE_MIGRATION, StandardCharsets.UTF_8);
        assertThat(migrationSql)
                .contains("status IN (3, 4, 6)")
                .contains("SET status = 7")
                .contains("8=已关闭")
                .contains("DELETE FROM marketing_account_occupancy")
                .contains("INSERT IGNORE INTO marketing_account_occupancy")
                .contains("ROW_NUMBER() OVER")
                .contains("task.status IN (1, 2, 5)")
                .contains("WHERE ranked.owner_rank = 1");
    }

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
                .contains("m.deleted_at IS NULL")
                .contains("TRIM(m.group_jid) &lt;&gt; ''")
                .contains("LEFT JOIN group_link g")
                .contains("(#{accountGroupSendAt} IS NULL OR m.joined_at &gt;= #{accountGroupSendAt})")
                .doesNotContain("account_group_baseline")
                .doesNotContain("account_state")
                .doesNotContain("group_link_health")
                .doesNotContain("membership_state");
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
                .contains("group_jid = COALESCE(NULLIF(TRIM(group_jid), ''), NULLIF(TRIM(#{groupJid}), ''))")
                .contains("group_status = #{groupStatus}")
                .contains("group_status_reason = #{groupStatusReason}")
                .contains("group_status_checked_at = #{groupStatusCheckedAt}");
        assertThat(markAttemptFailedSql)
                .contains("group_jid = COALESCE(NULLIF(TRIM(group_jid), ''), NULLIF(TRIM(#{groupJid}), ''))")
                .contains("group_status = #{groupStatus}")
                .contains("group_status_reason = #{groupStatusReason}")
                .contains("group_status_checked_at = #{groupStatusCheckedAt}");
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
    void successfulGroupCountUsesPersistedSuccessfulAttemptAndAtomicFactInsert() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String groupJidSql = selectBlock(xml, "selectSuccessfulAttemptGroupJid");
        String insertFactSql = insertBlock(xml, "insertSuccessfulGroupFromAttempt");
        String incrementSql = updateBlock(xml, "incrementTaskSuccessfulGroupCount");

        assertThat(groupJidSql)
                .contains("TRIM(group_jid)")
                .contains("marketing_task_id = #{taskId}")
                .contains("status = 1");
        assertThat(insertFactSql)
                .contains("INSERT IGNORE INTO marketing_task_success_group")
                .contains("FROM marketing_task_send_attempt a")
                .contains("a.id = #{attemptId}")
                .contains("a.tenant_id = #{tenantId}")
                .contains("a.marketing_task_id = #{taskId}")
                .contains("a.status = 1")
                .contains("TRIM(a.group_jid)")
                .contains("NOT EXISTS")
                .contains("FROM group_creation_marketing_item item")
                .contains("item.marketing_attempt_id = a.id");
        assertThat(incrementSql)
                .contains("target_group_count = target_group_count + 1")
                .doesNotContain("deleted_at IS NULL");
    }

    @Test
    void tenantInterceptorKeepsSuccessfulGroupInsertSelectTenantQualified() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
        String insertSql = sqlBody(insertBlock(xml, "insertSuccessfulGroupFromAttempt"))
                .replace("#{tenantId}", "7")
                .replace("#{taskId}", "42")
                .replace("#{attemptId}", "9001")
                .replace("#{now}", "1783159200000")
                .replace("&lt;&gt;", "<>");
        TenantLineInnerInterceptor interceptor = new TenantLineInnerInterceptor(() -> new LongValue(7L));

        String parsedSql = interceptor.parserSingle(insertSql, null);

        assertThat(parsedSql)
                .contains("created_at, tenant_id)")
                .contains("1783159200000, a.tenant_id")
                .contains("a.tenant_id = 7")
                .doesNotContain("1783159200000, tenant_id");
    }

    @Test
    void lifecycleMutationsUseFiveStateGuardsAndDoNotRewriteSchedule() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String startSql = updateBlock(xml, "startPendingTask");
        String pauseSql = updateBlock(xml, "pauseSendingTask");
        String resumeSql = updateBlock(xml, "resumePausedTask");
        String closeSql = updateBlock(xml, "closeActiveTask");
        String templateTerminationSql = updateBlock(xml, "completeActiveTasksByTemplateIds");
        String deleteSql = updateBlock(xml, "batchSoftDelete");

        assertThat(startSql)
                .contains("SET status = 2")
                .contains("status = 1")
                .contains("task_start_at &lt;= #{now}")
                .contains("task_end_at &gt; #{now}")
                .doesNotContain("task_start_at =")
                .doesNotContain("task_end_at =")
                .doesNotContain("account_group_send_at =");
        assertThat(pauseSql)
                .contains("SET status = 5")
                .contains("next_round_at = NULL")
                .contains("status = 2")
                .doesNotContain("finished_at =");
        assertThat(resumeSql)
                .contains("SET status = 2")
                .contains("next_round_at = #{now}")
                .contains("status = 5")
                .contains("task_start_at &lt;= #{now}")
                .contains("task_end_at &gt; #{now}");
        assertThat(closeSql)
                .contains("SET status = 8")
                .contains("status IN (1, 2, 5)")
                .contains("next_round_at = NULL")
                .contains("finished_at = #{now}");
        assertThat(templateTerminationSql)
                .contains("SET status = 7")
                .contains("status IN (1, 2, 5)")
                .contains("finished_at = COALESCE(finished_at, #{now})");
        assertThat(deleteSql)
                .contains("status IN (7, 8)")
                .doesNotContain("status &lt;&gt; 2");
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
    void pausedTaskIsCompletedWhenItsPlanExpires() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String selectSql = selectBlock(xml, "selectExpiredRunnableTasks");
        String updateSql = updateBlock(xml, "endExpiredTask");

        assertThat(selectSql).contains("status IN (1, 2, 5)");
        assertThat(updateSql).contains("status IN (1, 2, 5)");
    }

    @Test
    void detailRollupShowsOccupiedSkipsWithoutCountingThemAsFailures() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

        assertThat(sql)
                .contains("a.status IN (1, 2, 3)")
                .contains("SUM(CASE WHEN a.status = 2 THEN 1 ELSE 0 END) AS failedMessageCount")
                .contains("WHEN a.status IN (2, 3) THEN COALESCE")
                .doesNotContain("SUM(CASE WHEN a.status IN (2, 3)");
    }

    @Test
    void detailRollupClearsOldReasonWhenLatestCompletedAttemptSucceeded() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

        assertThat(sql)
                .contains("ELSE ''")
                .contains("a.id DESC")
                .contains("NULLIF(\n                   SUBSTRING_INDEX(")
                .contains("),\n                   ''\n               ) AS lastReason");
    }

    @Test
    void detailRollupUsesLatestAttemptGroupStatusAndHasMigration() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

        assertThat(GROUP_STATUS_MIGRATION).exists();
        assertThat(Files.readString(GROUP_STATUS_MIGRATION, StandardCharsets.UTF_8))
                .contains("ADD COLUMN group_status VARCHAR(32)")
                .contains("ADD COLUMN group_status_reason VARCHAR(64)")
                .contains("ADD COLUMN group_status_checked_at BIGINT");
        assertThat(sql)
                .contains("WHEN a.status IN (1, 2) THEN")
                .contains("COALESCE(NULLIF(TRIM(a.group_status), ''), 'UNCONFIRMED')")
                .contains("AS groupStatus")
                .contains("a.id DESC");
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

    private static String insertBlock(String xml, String id) {
        String startTag = "<insert id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).as("mapper insert " + id + " exists").isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</insert>", start);
        assertThat(end).as("mapper insert " + id + " closes").isGreaterThan(start);
        return xml.substring(start, end);
    }

    private static String sqlBody(String mapperBlock) {
        int start = mapperBlock.indexOf('>');
        assertThat(start).isGreaterThanOrEqualTo(0);
        return mapperBlock.substring(start + 1)
                .replaceAll("(?s)<!--.*?-->", "")
                .trim();
    }
}
