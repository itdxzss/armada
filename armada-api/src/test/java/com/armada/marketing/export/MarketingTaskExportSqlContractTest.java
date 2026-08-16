package com.armada.marketing.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.export.mapper.MarketingTaskExportMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.Test;

/** 营销任务导出迁移与事实查询的安全边界契约。 */
class MarketingTaskExportSqlContractTest {

    private static final Path JOB_MIGRATION = Path.of(
            "src/main/resources/db/migration/V083__marketing_task_export_job.sql");
    private static final String MAPPER_XML = "/mapper/marketing/MarketingTaskExportMapper.xml";

    @Test
    void migrationDefinesDurableTenantJobAndActiveRequestDeduplication() throws IOException {
        String sql = Files.readString(JOB_MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS marketing_task_export_job")
                .contains("snapshot_at BIGINT NOT NULL")
                .contains("claim_token CHAR(36)")
                .contains("GENERATED ALWAYS AS")
                .contains("IF(status IN ('PENDING', 'PROCESSING'), request_hash, NULL)")
                .contains("UNIQUE KEY uq_marketing_export_active")
                .contains("IF(status IN ('PENDING', 'PROCESSING'), created_by, NULL)")
                .contains("UNIQUE KEY uq_marketing_export_creator_active")
                .contains("(tenant_id, active_created_by)")
                .contains("tenant:marketing_task:export")
                .doesNotContain("CREATE TABLE IF NOT EXISTS marketing_task (")
                .doesNotContain("ALTER TABLE marketing_task ");
    }

    @Test
    void exportFactsUseSnapshotCutoffAndExplicitSameTenantJoins() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String groupStatusCase = sqlBlock(xml, "ExportGroupStatusCase");
        String countryRows = groupStatusCase + selectBlock(xml, "selectCountryEntryRows");
        String groupRows = groupStatusCase + sqlBlock(xml, "ExportGroupCtes")
                + selectBlock(xml, "selectGroupRows");

        assertThat(countryRows)
                .contains("a.tenant_id")
                .contains("mt.tenant_id = sm.tenant_id")
                .contains("jtr.tenant_id = sm.tenant_id")
                .contains("joined_account.tenant_id = jtr.tenant_id")
                .contains("a.result_at &lt;= #{snapshotAt}")
                .contains("COALESCE(jtr.joined_at, jtr.updated_at, jtr.created_at) &lt;= #{snapshotAt}")
                .contains("jtr.status = 'SUCCESS'")
                .contains("REGEXP_REPLACE")
                .contains("ROW_NUMBER() OVER")
                .contains("COUNT(*) OVER")
                .contains("sender_rank = 1")
                .contains("latest_group_status AS")
                .contains("status_rank = 1")
                .contains("ORDER BY effective_group_status DESC,")
                .contains("round_no DESC, attempt_no DESC, attempt_id DESC")
                .contains("CHAT_TERMINATED")
                .contains("ACCOUNT_NOT_PARTICIPANT")
                .contains("protocol.group_status_reason")
                .contains("health.banned = 1")
                .contains("health.health_status = 2")
                .contains("health.health_status = 3")
                .contains("sm.marketing_count AS marketingCount")
                .doesNotContain("COUNT(DISTINCT sm.attempt_id) AS marketingCount")
                .doesNotContain("MAX(membership.is_admin)")
                .doesNotContain("group_link_preview")
                .doesNotContain("group_link_health")
                .doesNotContain("account_group_membership");
        assertThat(groupRows)
                .contains("t.tenant_id = a.tenant_id")
                .contains("mt.tenant_id = d.tenant_id")
                .contains("attempts.tenant_id = d.tenant_id")
                .contains("joined.tenant_id = d.tenant_id")
                .contains("account_state.tenant_id = d.tenant_id")
                .contains("submitted_at &lt;= #{snapshotAt}")
                .contains("result_at &gt; #{snapshotAt}")
                .contains("COALESCE(jtr.joined_at, jtr.updated_at, jtr.created_at) &lt;= #{snapshotAt}")
                .contains("REGEXP_REPLACE")
                .contains("selected_group_jids AS")
                .contains("FROM selected_group_jids selected")
                .contains("COUNT(DISTINCT NULLIF(REGEXP_REPLACE")
                .contains("e.group_jid = COALESCE(")
                .contains("NULLIF(TRIM(t.group_jid), '')")
                .contains("NULLIF(TRIM(current_group.group_jid), '')")
                .doesNotContain("e.marketing_target_id = t.id")
                .contains("fixed.execution_group_invite_url")
                .contains("fixed.target_group_link_url")
                .contains("CONCAT('https://chat.whatsapp.com/', TRIM(current_invite.invite_code))")
                .contains("LOWER(TRIM(group_link.link_url))")
                .contains("END) AS groupLink")
                .contains("NULLIF(TRIM(health.subject), ''), '未命名群组') AS groupName")
                .contains("COALESCE(health.checked_member_count, health.member_count)")
                .contains("self_participant.role IN (2, 3)")
                .doesNotContain("group_link.link_url AS groupLink")
                .doesNotContain("COALESCE(latest.group_jid, fixed.group_jid, d.group_key)) AS groupName")
                .doesNotContain("GROUP BY mt.id, mt.task_name, mt.remark, target.id")
                .doesNotContain("HAVING groupJid IS NOT NULL")
                .doesNotContain("group_link_preview")
                .doesNotContain("group_link_health")
                .doesNotContain("account_group_membership");
    }

    @Test
    void complexExportQueriesUseExplicitTenantScopeWithoutInterceptorRewrite()
            throws IOException, NoSuchMethodException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String countryRows = selectBlock(xml, "selectCountryEntryRows");
        String groupCtes = sqlBlock(xml, "ExportGroupCtes");
        String memberRows = selectBlock(xml, "selectGroupMemberRows");

        assertThat(countryRows)
                .containsOnlyOnce("WHERE a.tenant_id = #{tenantId}\n              AND a.status = 1")
                .containsOnlyOnce("WHERE a.tenant_id = #{tenantId}\n              AND a.status IN (1, 2)");
        assertThat(groupCtes)
                .contains("WHERE a.tenant_id = #{tenantId}\n              AND (CASE WHEN a.status = 3")
                .contains("WHERE t.tenant_id = #{tenantId}\n              AND COALESCE(t.target_scope, 1) = 1");
        assertThat(memberRows)
                .contains("binding.tenant_id = #{tenantId}")
                .contains("member_account.tenant_id = binding.tenant_id")
                .contains("participant.last_exited_at &lt;= #{snapshotAt}\n"
                        + "                          AND participant.last_exit_type = 'REMOVED' THEN '被移出群组'")
                .contains("participant.last_exited_at &lt;= #{snapshotAt}\n"
                        + "                          AND participant.last_exit_type = 'LEFT'")
                .contains("participant.last_exited_at &lt;= #{snapshotAt}\n"
                        + "                          AND participant.last_exit_type = 'UNKNOWN' THEN '退出原因未识别'")
                .contains("binding.membership_active_since_at &lt;= #{snapshotAt}")
                .doesNotContain("account_group_membership");

        assertTenantInterceptorIgnored("selectCountryEntryRows",
                Long.class, List.class, long.class, ResultHandler.class);
        assertTenantInterceptorIgnored("selectGroupRows",
                Long.class, List.class, long.class, ResultHandler.class);
        assertTenantInterceptorIgnored("selectGroupMemberRows",
                Long.class, List.class, long.class, ResultHandler.class);
        assertTenantInterceptorIgnored("selectGroupRowsList",
                Long.class, List.class, long.class);
    }

    @Test
    void realtimeMemberProviderQueriesAllTaskGroupsAndRanksTwoActualSenders() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String groups = selectBlock(xml, "selectGroupRowsList");
        String observers = sqlBlock(xml, "ExportGroupObserverCtes")
                + selectBlock(xml, "selectGroupRowsList");

        assertThat(groups)
                .contains("groupJid")
                .contains("FROM group_rows group_data")
                .contains("ORDER BY group_data.taskId ASC,")
                .contains("group_data.groupKey ASC");
        assertThat(observers)
                .contains("attempts.projected_status = 1")
                .contains("PARTITION BY taskId, groupJid, accountId")
                .contains("PARTITION BY taskId, groupJid")
                .contains("candidateRank &lt;= 2")
                .contains("attempts.account_id IS NOT NULL")
                .doesNotContain("UPPER(TRIM(observer_account.protocol_id)) = 'ANDROID'")
                .contains("observer_state.account_state = 2")
                .contains("observer_state.login_state = 1")
                .contains("observer_group.group_jid = source.groupJid")
                .contains("observer_participant.presence_status = 1")
                .doesNotContain("account_group_membership");
    }

    @Test
    void groupMemberQueryAvoidsMysqlReservedWordAlias() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String memberRows = selectBlock(xml, "selectGroupMemberRows");

        assertThat(memberRows)
                .contains("FROM group_rows group_data")
                .doesNotContain("FROM group_rows groups")
                .doesNotContain("groups.");
    }

    @Test
    void largeDetailQueriesUseConnectorJRowByRowStreaming() throws NoSuchMethodException {
        assertStreamingOptions("selectCountryEntryRows");
        assertStreamingOptions("selectGroupRows");
        assertStreamingOptions("selectGroupMemberRows");
    }

    @Test
    void workerCompletionUsesClaimTokenFencing() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(updateBlock(xml, "claimJob"))
                .contains("claim_token = #{claimToken}");
        assertThat(updateBlock(xml, "renewJobLease"))
                .contains("status = 'PROCESSING'")
                .contains("claim_token = #{claimToken}")
                .contains("lease_until = #{leaseUntil}");
        assertThat(updateBlock(xml, "markJobSuccess"))
                .contains("claim_token = #{claimToken}");
        assertThat(updateBlock(xml, "markJobFailed"))
                .contains("claim_token = #{claimToken}");
    }

    @Test
    void exhaustedWorkerLeasesBecomeTerminalFailures() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String update = updateBlock(xml, "markExhaustedJobs");

        assertThat(update)
                .contains("status = 'FAILED'")
                .contains("lease_until &lt; #{now}")
                .contains("attempt_count &gt;= 3");
    }

    @Test
    void expiredFilesAreSelectedAndClearedWithTenantAndStorageKeyGuards() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String select = selectBlock(xml, "selectExpiredFiles");
        String update = updateBlock(xml, "clearExpiredStorage");

        assertThat(select)
                .contains("status = 'SUCCESS'")
                .contains("expires_at &lt;= #{now}")
                .contains("storage_key IS NOT NULL")
                .contains("LIMIT #{limit}");
        assertThat(update)
                .contains("tenant_id = #{tenantId}")
                .contains("id = #{id}")
                .contains("storage_key = #{storageKey}")
                .contains("expires_at &lt;= #{now}");
    }

    private static String selectBlock(String xml, String id) {
        return block(xml, "select", id);
    }

    private static String updateBlock(String xml, String id) {
        return block(xml, "update", id);
    }

    private static String sqlBlock(String xml, String id) {
        return block(xml, "sql", id);
    }

    private static String block(String xml, String tag, String id) {
        String startTag = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).as("mapper %s %s exists", tag, id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</" + tag + ">", start);
        assertThat(end).as("mapper %s %s closes", tag, id).isGreaterThan(start);
        return xml.substring(start, end);
    }

    private static void assertStreamingOptions(String methodName) throws NoSuchMethodException {
        Method method = MarketingTaskExportMapper.class.getMethod(
                methodName, Long.class, List.class, long.class, ResultHandler.class);
        Options options = method.getAnnotation(Options.class);

        assertThat(options).isNotNull();
        assertThat(options.fetchSize()).isEqualTo(Integer.MIN_VALUE);
        assertThat(options.resultSetType()).isEqualTo(ResultSetType.FORWARD_ONLY);
    }

    private static void assertTenantInterceptorIgnored(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = MarketingTaskExportMapper.class.getMethod(methodName, parameterTypes);
        InterceptorIgnore annotation = method.getAnnotation(InterceptorIgnore.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.tenantLine()).isEqualTo("true");
    }
}
