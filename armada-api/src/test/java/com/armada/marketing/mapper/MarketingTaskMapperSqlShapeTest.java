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
    private static final Path ACCOUNT_GROUP_INTERVAL_MIGRATION = Path.of(
            "src/main/resources/db/migration/V058__marketing_account_group_send_interval.sql");
    private static final Path IMMEDIATE_ROUND_MIGRATION = Path.of(
            "src/main/resources/db/migration/V059__marketing_new_group_immediate_round.sql");

    @Test
    void immediateRoundMigrationReservesZeroForNewGroupSend() throws IOException {
        assertThat(IMMEDIATE_ROUND_MIGRATION).exists();
        assertThat(Files.readString(IMMEDIATE_ROUND_MIGRATION, StandardCharsets.UTF_8))
                .contains("MODIFY COLUMN round_no BIGINT NOT NULL DEFAULT 0")
                .contains("营销轮次:0=新群首次即时发送 1+=正常任务轮次");
    }

    @Test
    void accountGroupSendIntervalIsPersistedInMillisecondsWithForwardMigration() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("<result column=\"account_group_send_interval_ms\" property=\"accountGroupSendIntervalMs\"/>")
                .contains("send_per_round, account_group_send_interval_ms, send_interval_seconds")
                .contains("#{sendPerRound}, #{accountGroupSendIntervalMs}, #{sendIntervalSeconds}");
        assertThat(ACCOUNT_GROUP_INTERVAL_MIGRATION).exists();
        assertThat(Files.readString(ACCOUNT_GROUP_INTERVAL_MIGRATION, StandardCharsets.UTF_8))
                .contains("information_schema.columns")
                .contains("column_name = 'account_group_send_interval_ms'")
                .contains("ADD COLUMN account_group_send_interval_ms INT NOT NULL DEFAULT 500");
    }

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
    void immediateTargetQueryUsesAccountOccupancyWithoutGlobalTaskScan() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectOwnedSendingDynamicTarget");

        assertThat(sql)
                .contains("WHERE t.account_id = #{accountId}")
                .contains("t.target_scope = 2")
                .contains("JOIN marketing_account_occupancy o ON o.account_id = t.account_id")
                .contains("o.marketing_task_id = mt.id")
                .contains("mt.status = 2")
                .contains("mt.status = 5")
                .contains("mt.is_new_group_delay_enabled = 1")
                .contains("mt.task_start_at IS NULL OR mt.task_start_at &lt;= #{now}")
                .contains("mt.task_end_at IS NULL OR mt.task_end_at &gt; #{now}")
                .contains("LIMIT 1")
                .doesNotContain("account_group_membership");
    }

    @Test
    void immediateRetryAtomicallyReplacesOnlyFirstSubmittedCommand() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String resubmitSql = updateBlock(xml, "resubmitImmediateAttempt");
        String targetSql = selectBlock(xml, "selectTargetById");

        assertThat(resubmitSql)
                .contains("SET attempt_no = 2")
                .contains("is_retry = 1")
                .contains("command_id = #{newCommandId}")
                .contains("round_no = 0")
                .contains("attempt_no = 1")
                .contains("is_retry = 0")
                .contains("command_id = #{expectedCommandId}")
                .contains("status = 0");
        assertThat(targetSql)
                .contains("<include refid=\"TargetColumns\"/>")
                .contains("JOIN account a ON a.id = t.account_id")
                .contains("WHERE t.id = #{targetId}");
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
    void marketingAccountSelectionUsesCurrentGroupFacts() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String candidateSql = selectBlock(xml, "selectTargetCandidate");
        String accountCandidateSql = selectBlock(xml, "selectAccountTargetCandidate");
        String dynamicTargetSql = selectBlock(xml, "selectDynamicTargetGroups");
        String currentTargetSql = selectBlock(xml, "selectCurrentTargetGroup");
        String currentTargetByJidSql = selectBlock(xml, "selectCurrentTargetGroupByJid");
        String treeAccountSql = selectBlock(xml, "selectAccountTreeAccounts");

        assertThat(candidateSql)
                .contains("JOIN wa_account_group_binding binding ON binding.account_id = a.id")
                .contains("current_group.group_jid AS groupJid")
                .contains("self_participant.presence_status")
                .contains("a.account_group_id = #{accountGroupId}");
        assertThat(candidateSql)
                .contains("collection=\"selectableAccountStates\"")
                .doesNotContain("s.account_state = 2")
                .doesNotContain("group_link_health")
                .doesNotContain("account_group_baseline")
                .doesNotContain("account_group_membership");
        assertThat(accountCandidateSql)
                .contains("collection=\"selectableAccountStates\"")
                .doesNotContain("s.account_state = 2");
        assertThat(dynamicTargetSql)
                .contains("JOIN wa_account_group_binding binding")
                .contains("current_group.group_jid AS groupJid")
                .contains("LEFT JOIN group_link g ON g.group_id = binding.group_id")
                .contains("binding.membership_active_since_at &gt;= #{accountGroupSendAt}")
                .doesNotContain("account_group_baseline")
                .doesNotContain("account_group_membership")
                .doesNotContain("account_state")
                .doesNotContain("group_link_health")
                .doesNotContain("membership_state");
        assertThat(currentTargetSql)
                .contains("JOIN wa_account_group_binding binding")
                .contains("g.id = #{groupLinkId}")
                .contains("self_participant.presence_status IN (0, 1)")
                .contains("binding.was_in_initial_baseline")
                .doesNotContain("account_group_membership")
                .doesNotContain("account_group_baseline")
                .contains("WHERE a.id = #{accountId}");
        assertThat(currentTargetByJidSql)
                .contains("JOIN wa_group current_group ON current_group.group_jid = #{groupJid}")
                .contains("JOIN wa_account_group_binding binding")
                .contains("self_participant.presence_status IN (0, 1)")
                .contains("NULL AS groupLinkId")
                .doesNotContain("JOIN group_link")
                .doesNotContain("account_group_membership")
                .contains("WHERE a.id = #{accountId}");
        assertThat(treeAccountSql).contains("a.protocol_account_id AS protocolAccountId");
        assertThat(treeAccountSql)
                .contains("wa_account_group_binding")
                .contains("COALESCE(gm.groupCount, 0) AS groupCount")
                .contains("s.login_state AS loginState")
                .doesNotContain("account_group_membership")
                .doesNotContain("account_group_baseline");
    }

    @Test
    void sendResultRollupBackfillsAttemptJidAndTargetGroupSnapshot() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String markAttemptSuccessSql = updateBlock(xml, "markAttemptSuccess");
        String markAttemptFailedSql = updateBlock(xml, "markAttemptFailed");
        String targetLockSql = selectBlock(xml, "selectTargetForResultUpdate");
        String targetSnapshotSql = selectBlock(xml, "selectTargetResultSnapshot");
        String markTargetSuccessSql = updateBlock(xml, "updateTargetSuccessFromSnapshot");
        String markTargetFailedSql = updateBlock(xml, "updateTargetFailedFromSnapshot");

        assertThat(markAttemptSuccessSql)
                .contains("group_jid = COALESCE(NULLIF(TRIM(group_jid), ''), NULLIF(TRIM(#{groupJid}), ''))")
                .contains("group_status = #{groupStatus}")
                .contains("group_status_reason = #{groupStatusReason}")
                .contains("group_status_checked_at = #{groupStatusCheckedAt}")
                .contains("AND command_id = #{commandId}");
        assertThat(markAttemptFailedSql)
                .contains("group_jid = COALESCE(NULLIF(TRIM(group_jid), ''), NULLIF(TRIM(#{groupJid}), ''))")
                .contains("group_status = #{groupStatus}")
                .contains("group_status_reason = #{groupStatusReason}")
                .contains("group_status_checked_at = #{groupStatusCheckedAt}")
                .contains("AND command_id = #{commandId}");
        assertThat(targetLockSql)
                .contains("FROM marketing_task_target")
                .contains("WHERE id = #{targetId}")
                .contains("FOR UPDATE")
                .doesNotContain("JOIN");
        assertThat(targetSnapshotSql.replaceAll("\\s+", " "))
                .contains("FROM marketing_task_send_attempt a")
                .contains("LEFT JOIN wa_group jid_group")
                .contains("LEFT JOIN group_link resolved_handle")
                .contains("LEFT JOIN wa_group_profile current_profile")
                .contains("LEFT JOIN group_link g ON g.id = COALESCE(a.group_link_id, resolved_handle.id, #{target.groupLinkId})")
                .contains("COALESCE(a.group_link_id, resolved_handle.id, #{target.groupLinkId})")
                .contains("CASE WHEN LOWER(TRIM(#{target.groupLinkUrl})) LIKE 'https://chat.whatsapp.com/%'")
                .contains("CASE WHEN LOWER(TRIM(g.link_url)) LIKE 'https://chat.whatsapp.com/%'")
                .contains("#{target.groupLinkId}, -1)")
                .contains("= COALESCE(#{target.groupLinkId}, -1)")
                .contains("= COALESCE(NULLIF(TRIM(#{target.groupJid}), ''), '')")
                .contains("'') AS groupLinkUrl")
                .contains("COALESCE(NULLIF(TRIM(a.group_name), ''), NULLIF(TRIM(g.group_name), ''), current_profile.subject, #{target.groupName})")
                .doesNotContain("group_link_preview")
                .doesNotContain("COALESCE(g.link_url, #{target.groupLinkUrl})")
                .doesNotContain("FOR UPDATE");
        assertThat(markTargetSuccessSql)
                .contains("UPDATE marketing_task_target")
                .contains("group_link_id = COALESCE(#{snapshot.groupLinkId}, group_link_id)")
                .contains("group_link_url = COALESCE(#{snapshot.groupLinkUrl}, group_link_url)")
                .doesNotContain("JOIN marketing_task_send_attempt")
                .doesNotContain("group_link_preview")
                .doesNotContain("group_link g");
        assertThat(markTargetFailedSql)
                .contains("UPDATE marketing_task_target")
                .contains("group_link_id = COALESCE(#{snapshot.groupLinkId}, group_link_id)")
                .contains("group_link_url = COALESCE(#{snapshot.groupLinkUrl}, group_link_url)")
                .doesNotContain("JOIN marketing_task_send_attempt")
                .doesNotContain("group_link_preview")
                .doesNotContain("group_link g");
    }

    @Test
    void tenantInterceptorParsesTargetLockAndNonLockingSnapshot() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
        String targetLockSql = sqlBody(selectBlock(xml, "selectTargetForResultUpdate"))
                .replace("#{targetId}", "501");
        String snapshotSql = sqlBody(selectBlock(xml, "selectTargetResultSnapshot"))
                .replace("#{attemptId}", "9001")
                .replace("#{target.id}", "501")
                .replace("#{target.groupLinkId}", "101")
                .replace("#{target.groupJid}", "'120363001@g.us'")
                .replace("#{target.groupLinkUrl}", "'https://chat.whatsapp.com/example'")
                .replace("#{target.groupName}", "'测试群'");
        TenantLineInnerInterceptor interceptor = new TenantLineInnerInterceptor(() -> new LongValue(7L));

        String parsedTargetLockSql = interceptor.parserSingle(targetLockSql, null);
        String parsedSql = interceptor.parserSingle(snapshotSql, null);

        assertThat(parsedTargetLockSql)
                .contains("tenant_id = 7")
                .contains("FOR UPDATE");
        assertThat(parsedSql)
                .contains("a.tenant_id = 7")
                .contains("p.tenant_id = 7")
                .contains("g.tenant_id = 7")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    void tenantInterceptorParsesCurrentTargetByJid() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
        String sql = sqlBody(selectBlock(xml, "selectCurrentTargetGroupByJid"))
                .replace("#{accountId}", "5001")
                .replace("#{groupJid}", "'120363001@g.us'")
                .replace("&lt;&gt;", "<>");
        TenantLineInnerInterceptor interceptor = new TenantLineInnerInterceptor(() -> new LongValue(7L));

        String parsedSql = interceptor.parserSingle(sql, null);

        assertThat(parsedSql)
                .contains("a.tenant_id = 7")
                .contains("s.tenant_id = 7")
                .contains("group_sync.tenant_id = 7")
                .contains("current_group.tenant_id = 7")
                .contains("binding.tenant_id = 7")
                .contains("self_participant.tenant_id = 7")
                .doesNotContain("FOR UPDATE");
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
                .contains("a.status IN (0, 1, 2, 3, 4)")
                .contains("SUM(CASE WHEN attemptStatus = 2 THEN 1 ELSE 0 END) AS failedMessageCount")
                .contains("SUM(CASE WHEN attemptStatus = 3 THEN 1 ELSE 0 END) AS skippedMessageCount")
                .contains("WHEN attemptStatus IN (2, 3) THEN COALESCE")
                .contains("WHERE attemptStatus IN (1, 2, 3, 4)")
                .doesNotContain("SUM(CASE WHEN attemptStatus IN (2, 3)")
                .doesNotContain("SUM(CASE WHEN attemptStatus = 4");
    }

    @Test
    void detailRollupClearsOldReasonWhenLatestCompletedAttemptSucceeded() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

        assertThat(sql)
                .contains("WHEN attemptStatus = 1 THEN ''")
                .contains("ORDER BY eventAt DESC, attemptId DESC")
                .contains(") AS lastReason");
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
                .contains("a.group_status AS rawGroupStatus")
                .contains("a.group_status_reason AS groupStatusReason")
                .contains("WHERE attemptStatus IN (1, 2)")
                .contains("protocol.rawGroupStatus AS groupStatus")
                .contains("protocol.groupStatusReason AS groupStatusReason");
    }

    @Test
    void detailRollupUsesLatestEffectiveRoundForExecutionResult() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

        assertThat(sql)
                .contains("ROW_NUMBER() OVER")
                .contains("roundNo DESC, attemptNo DESC, attemptId DESC")
                .contains("protocol.attemptStatus AS latestAttemptStatus")
                .contains("ended.attemptStatus AS latestExecutionStatus")
                .contains("CASE WHEN ended.attemptStatus = 4 THEN 0 ELSE 1 END ASC")
                .doesNotContain("AS executionResult");
    }

    @Test
    void detailRollupJoinsOneLatestEffectiveAttemptForAllDerivedFields() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

        assertThat(sql)
                .contains("a.status IN (0, 1, 2, 3, 4)")
                .contains("latest_protocol AS")
                .contains("latest_ended AS")
                .contains("WHERE attemptStatus IN (1, 2)")
                .contains("WHERE attemptStatus IN (1, 2, 3, 4)")
                .contains("PARTITION BY tenant_id, accountId, groupKey")
                .contains("ORDER BY CASE WHEN attemptStatus = 4 THEN 0 ELSE 1 END ASC")
                .contains("roundNo DESC, attemptNo DESC, attemptId DESC")
                .contains("protocol.attemptStatus AS latestAttemptStatus")
                .contains("protocol.reasonCode AS reasonCode")
                .contains("protocol.reasonMessage AS reasonMessage")
                .contains("protocol.rawGroupStatus AS groupStatus")
                .contains("protocol.groupStatusReason AS groupStatusReason")
                .contains("ended.attemptStatus AS latestExecutionStatus")
                .contains("SUM(CASE WHEN attemptStatus = 1 THEN 1 ELSE 0 END) AS sentMessageCount")
                .contains("SUM(CASE WHEN attemptStatus = 3 THEN 1 ELSE 0 END) AS skippedMessageCount")
                .contains("MAX(CASE WHEN attemptStatus = 1 THEN eventAt ELSE NULL END) AS lastSentAt");
    }

    @Test
    void detailRollupPrefersEffectiveGroupEvidenceAndKeepsExecutionEvidenceIndependent()
            throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

        assertThat(sql)
                .contains("END AS effectiveGroupStatus")
                .contains("effectiveGroupStatus DESC")
                .contains("'ACCOUNT_BANNED'")
                .contains("'GROUP_SEND_ALLOWED'")
                .contains("ended.rawGroupStatus AS executionGroupStatus")
                .contains("ended.groupStatusReason AS executionGroupStatusReason")
                .doesNotContain("'ACCOUNT_OFFLINE'")
                .doesNotContain("'STATUS_RESOLUTION_UNAVAILABLE'");
    }

    @Test
    void detailRollupKeepsTenantColumnAvailableForInterceptorGeneratedFilters() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
        String detailSql = sqlBody(selectBlock(xml, "selectAccountGroupStatsByTaskId"))
                .replace("#{taskId}", "42");
        TenantLineInnerInterceptor interceptor = new TenantLineInnerInterceptor(() -> new LongValue(7L));

        String parsedSql = interceptor.parserSingle(detailSql, null);

        assertThat(parsedSql)
                .contains("t.tenant_id AS tenant_id")
                .contains("FROM attempt_facts WHERE tenant_id = 7")
                .contains("protocol.tenant_id = 7")
                .contains("ended.tenant_id = 7")
                .contains("binding.tenant_id = 7")
                .contains("self_participant.tenant_id = 7")
                .contains("d.tenant_id = 7");
    }

    @Test
    void waitingAttemptLockKeepsExplicitTenantBeforeMysqlOrderAndForUpdate() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectWaitingAttemptsForUpdate");

        assertThat(sql)
                .contains("tenant_id = #{tenantId}")
                .contains("ORDER BY scheduled_send_at ASC, id ASC\n        FOR UPDATE");
    }

    @Test
    void dynamicWaitingAttemptNameNeverFallsBackToAnotherTargetGroup() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectAccountGroupStatsByTaskId");

        assertThat(sql)
                .contains("WHEN a.round_no = 0\n"
                        + "                            AND a.group_link_id IS NULL\n"
                        + "                            AND NULLIF(TRIM(a.group_jid), '') IS NOT NULL THEN")
                .contains("NULLIF(TRIM(current_profile.subject), '')")
                .contains("NULLIF(TRIM(current_group.display_name), '')")
                .contains("ELSE COALESCE(\n"
                        + "                           NULLIF(TRIM(a.group_name), ''),\n"
                        + "                           NULLIF(TRIM(g.group_name), '')")
                .contains("NULLIF(TRIM(t.group_name), '')");
    }

    @Test
    void dynamicTargetUsesCompletedFirstSendAsMembershipTimeFallback() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String sql = selectBlock(xml, "selectDynamicTargetGroups");

        assertThat(sql)
                .contains("OR EXISTS (\n"
                        + "                      SELECT 1\n"
                        + "                      FROM marketing_task_send_attempt first_send")
                .contains("first_send.target_id = #{targetId}")
                .contains("first_send.round_no = 0")
                .contains("first_send.group_jid) = TRIM(current_group.group_jid)")
                .contains("delayed_send.scheduled_send_at &gt; delayed_send.detected_at")
                .contains("delayed_send.status = 4")
                .contains("delayed_send.attempted_at &gt;= #{ordinaryRoundDueAt}");
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
