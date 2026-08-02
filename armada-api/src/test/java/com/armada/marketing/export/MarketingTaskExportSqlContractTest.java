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
    private static final Path MEMBER_MIGRATION = Path.of(
            "src/main/resources/db/migration/V090__whatsapp_group_member_snapshot.sql");
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
                .contains("member.tenant_id = sm.tenant_id")
                .contains("member.group_jid = sm.group_jid")
                .contains("a.result_at &lt;= #{snapshotAt}")
                .contains("fact.occurred_at &lt;= #{snapshotAt}")
                .contains("country_member_state")
                .contains("member.member_jid")
                .contains("REGEXP_REPLACE")
                .contains("ROW_NUMBER() OVER")
                .contains("COUNT(*) OVER")
                .contains("sender_rank = 1")
                .contains("latest_group_status AS")
                .contains("status_rank = 1")
                .contains("country_sender_setting_ranked AS")
                .contains("selected.sender_account_id = snapshot.observer_account_id")
                .contains("sender_relation.account_id = sm.sender_account_id")
                .contains("AND COALESCE(")
                .contains("sender_member.is_admin,")
                .contains("sender_setting.observer_is_admin = 1")
                .contains("sender_relation.status_updated_at &gt; #{snapshotAt}")
                .contains("sender_setting.observer_is_admin, sender_setting.snapshot_at,")
                .contains("sender_relation.id, sender_relation.membership_status,")
                .contains("sender_relation.joined_at, sender_relation.status_updated_at,")
                .contains("member.membership_status IN (1, 5)")
                .contains("ORDER BY effective_group_status DESC,")
                .contains("round_no DESC, attempt_no DESC, attempt_id DESC")
                .contains("CHAT_TERMINATED")
                .contains("ACCOUNT_NOT_PARTICIPANT")
                .contains("protocol.group_status_reason")
                .contains("health.health_status = 2")
                .contains("health.health_status = 3")
                .contains("sm.marketing_count AS marketingCount")
                .doesNotContain("JOIN join_task_result jtr")
                .doesNotContain("JOIN account joined_account")
                .doesNotContain("COUNT(DISTINCT sm.attempt_id) AS marketingCount")
                .doesNotContain("MAX(membership.is_admin)");
        assertThat(groupRows)
                .contains("t.tenant_id = a.tenant_id")
                .contains("mt.tenant_id = d.tenant_id")
                .contains("attempts.tenant_id = d.tenant_id")
                .contains("members.tenant_id = d.tenant_id")
                .contains("account_state.tenant_id = d.tenant_id")
                .contains("submitted_at &lt;= #{snapshotAt}")
                .contains("result_at &gt; #{snapshotAt}")
                .contains("whatsapp_group_member_fact")
                .contains("whatsapp_group_member_snapshot_fact")
                .contains("selected_group_jids AS")
                .contains("fact.occurred_at &lt;= #{snapshotAt}")
                .contains("COUNT(DISTINCT member.member_jid)")
                .contains("CASE WHEN complete.group_jid IS NULL THEN NULL")
                .contains("latest_group_setting AS")
                .contains("sender_group_setting_ranked AS")
                .contains("sender_setting.observer_account_id = COALESCE(latest.account_id, fixed.sender_account_id)")
                .contains("sender_relation.account_id = COALESCE(latest.account_id, fixed.sender_account_id)")
                .contains("AND COALESCE(")
                .contains("sender_member.is_admin,")
                .contains("sender_setting.observer_is_admin = 1")
                .contains("sender_relation.status_updated_at &gt; #{snapshotAt}")
                .contains("current_member_roles AS")
                .contains("members.current_count AS groupMemberCount")
                .doesNotContain("health.current_count, preview.member_size")
                .doesNotContain("GROUP BY mt.id, mt.task_name, mt.remark, target.id")
                .doesNotContain("HAVING groupJid IS NOT NULL");
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
                .contains("member.tenant_id = #{tenantId}")
                .contains("JOIN member_state member")
                .contains("member.membership_status IN (3, 4)\n"
                        + "                          AND member.status_source = 'PARTICIPANT_REMOVE'")
                .contains("member.membership_status IN (3, 4)\n"
                        + "                          AND member.status_source = 'PARTICIPANT_LEAVE'")
                .contains("identity.phone AS memberPhone")
                .contains("member.membership_status IN (1, 5)")
                .contains("successful_join.joined_at BETWEEN")
                .doesNotContain("JOIN account member_account");

        assertTenantInterceptorIgnored("selectCountryEntryRows",
                Long.class, List.class, long.class, ResultHandler.class);
        assertTenantInterceptorIgnored("selectGroupRows",
                Long.class, List.class, long.class, ResultHandler.class);
        assertTenantInterceptorIgnored("selectGroupMemberRows",
                Long.class, List.class, long.class, ResultHandler.class);
    }

    @Test
    void whatsappMemberMigrationDefinesTenantIdentityAndExportIndexes() throws IOException {
        String sql = Files.readString(MEMBER_MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS whatsapp_group_member")
                .contains("tenant_id BIGINT NOT NULL")
                .contains("member_jid VARCHAR(128) NOT NULL")
                .contains("membership_status TINYINT NOT NULL")
                .contains("status_updated_at BIGINT NOT NULL")
                .contains("joined_at BIGINT")
                .contains("last_exited_at BIGINT")
                .contains("status_source_event_id VARCHAR(191) NOT NULL")
                .contains("announce_only TINYINT(1)")
                .contains("observer_is_admin TINYINT(1)")
                .contains("UNIQUE KEY uq_whatsapp_group_member_identity (tenant_id, group_jid, member_jid)")
                .contains("KEY idx_whatsapp_group_member_group_status")
                .contains("KEY idx_whatsapp_group_member_phone")
                .contains("CREATE TABLE IF NOT EXISTS whatsapp_group_member_fact")
                .contains("UNIQUE KEY uq_whatsapp_group_member_fact_event")
                .contains("CREATE TABLE IF NOT EXISTS whatsapp_group_member_snapshot_fact")
                .contains("KEY idx_whatsapp_group_member_snapshot_time");
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
