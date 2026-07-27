package com.armada.marketing.grouppull.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 拉群营销生命周期 SQL 的状态条件测试。 */
class GroupPullMarketingLifecycleSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/GroupPullMarketingMapper.xml";

    private static final String MARKETING_TASK_MAPPER_XML = "/mapper/marketing/MarketingTaskMapper.xml";

    private static final String OUTBOX_MAPPER_XML =
            "/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml";

    @Test
    void lifecycleUpdatesUseExpectedStatusAndBusinessType() throws IOException {
        String xml = readResource(MAPPER_XML);

        assertThat(block(xml, "select", "selectTaskForUpdate"))
                .contains("business_type = 2")
                .contains("FOR UPDATE");
        assertThat(block(xml, "update", "startTask"))
                .contains("business_type = 2")
                .contains("status = 1");
        assertThat(block(xml, "update", "pauseTask"))
                .contains("status = 2");
        assertThat(block(xml, "update", "resumeTask"))
                .contains("status = 5");
        assertThat(block(xml, "update", "requestRelease"))
                .contains("status IN (2, 5)")
                .contains("next_round_at = NULL");
        assertThat(block(xml, "update", "softDeletePendingTask"))
                .contains("status = 1");
    }

    @Test
    void releaseUpdatesAreRestrictedToTheCurrentTenantAndTask() throws IOException {
        String outboxXml = readResource(OUTBOX_MAPPER_XML);
        assertThat(block(outboxXml, "update", "cancelPendingMarketingTaskCommandsInternal"))
                .contains("attempt.tenant_id = #{tenantId}")
                .contains("outbox_row.tenant_id = #{tenantId}")
                .contains("attempt.marketing_task_id = #{marketingTaskId}")
                .contains("outbox_row.status = #{pendingStatus}");

        String marketingTaskXml = readResource(MARKETING_TASK_MAPPER_XML);
        assertThat(block(marketingTaskXml, "update", "markCanceledOutboxAttemptsSkipped"))
                .contains("attempt.tenant_id = #{tenantId}")
                .contains("outbox_row.tenant_id = #{tenantId}")
                .contains("attempt.marketing_task_id = #{taskId}");
        assertThat(block(marketingTaskXml, "update", "markDeadOutboxAttemptsFailed"))
                .contains("attempt.tenant_id = #{tenantId}")
                .contains("outbox_row.tenant_id = #{tenantId}")
                .contains("attempt.marketing_task_id = #{taskId}");
    }

    @Test
    void releaseCandidateUsesTenantPluginWithoutRowLock() throws Exception {
        Method method = GroupPullMarketingMapper.class.getMethod(
                "selectCancelableExecutions", Long.class);
        InterceptorIgnore ignore = method.getAnnotation(InterceptorIgnore.class);
        String xml = readResource(MAPPER_XML);
        String candidateSql = block(xml, "select", "selectCancelableExecutions");

        assertThat(ignore).isNull();
        assertThat(candidateSql)
                .contains("task_id = #{taskId}")
                .contains("group_name IS NULL")
                .contains("execution_status IN (1, 2)")
                .contains("ORDER BY id")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    void onlyTaskLookupKeepsExplicitForUpdate() throws IOException {
        String xml = readResource(MAPPER_XML);

        assertThat(selectIdsContainingForUpdate(xml)).containsExactly("selectTaskForUpdate");
    }

    @Test
    void groupDetailKeepsFormalFailuresAndPreservesFailureStage() throws IOException {
        String xml = readResource(MAPPER_XML);

        assertThat(block(xml, "select", "countTaskGroups"))
                .contains("task_id = #{taskId}")
                .contains("group_name IS NOT NULL");
        assertThat(block(xml, "select", "selectTaskGroups"))
                .contains("execution.group_name IS NOT NULL")
                .contains("relation.entry_status = 2")
                .contains("execution.group_member_count AS groupMemberCount")
                .doesNotContain("COALESCE(execution.group_member_count")
                .contains("ORDER BY execution.id ASC")
                .contains("LIMIT #{query.pageSize} OFFSET #{query.offset}");
        assertThat(block(xml, "update", "markExecutionTerminal"))
                .contains("current_stage = #{terminalStage}")
                .contains("execution_status = #{expectedStatus}")
                .contains("current_stage = #{expectedStage}")
                .doesNotContain("current_stage = 11");
    }

    @Test
    void materialEntryQueriesAreSingleRowConditionalAndPersistRetrySchedule() throws IOException {
        String xml = readResource(MAPPER_XML);

        assertThat(block(xml, "select", "selectNextPendingExecutionMaterial"))
                .contains("em.entry_status = 1")
                .contains("ORDER BY em.allocation_no ASC")
                .contains("LIMIT 1");
        assertThat(block(xml, "update", "updateMaterialStageProgress"))
                .contains("stage_retry_count = #{nextRetryCount}")
                .contains("stage_retry_count = #{expectedRetryCount}")
                .contains("next_execute_at = #{nextExecuteAt}")
                .contains("current_stage = #{expectedStage}");
        assertThat(block(xml, "update", "rescheduleMaterialExecutionsOnResume"))
                .contains("current_stage = 5")
                .contains("relation.entry_status = 1")
                .contains("RAND()")
                .contains("next_execute_at");
        assertThat(block(xml, "update", "updateMaterialEntryResult"))
                .contains("entry_status = 1")
                .doesNotContain("entry_status != 2");
        assertThat(block(xml, "update", "markGroupCreated"))
                .contains("next_execute_at = #{nextExecuteAt}");
    }

    @Test
    void pausedTasksDoNotDispatchMaterialEntryAndTerminalTasksCanCloseIt() throws IOException {
        String xml = readResource(MAPPER_XML);
        String dueSql = block(xml, "select", "selectDueExecutionDispatches");

        assertThat(dueSql)
                .contains("execution.current_stage &lt;&gt; 5")
                .contains("task.status &lt;&gt; 5")
                .contains("pull_task.resource_status = 3");
    }

    @Test
    void terminalMaterialCleanupIsConditionalAndTaskRuntimeIsLightweight() throws IOException {
        String xml = readResource(MAPPER_XML);

        assertThat(block(xml, "select", "selectTaskRuntime"))
                .contains("task_end_at")
                .contains("business_type = 2")
                .doesNotContain("FOR UPDATE");
        assertThat(block(xml, "update", "failPendingExecutionMaterials"))
                .contains("entry_status = 3")
                .contains("entry_status = 1");
    }

    private String readResource(String path) throws IOException {
        return new String(
                getClass().getResourceAsStream(path).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private String block(String xml, String tag, String id) {
        String open = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(open);
        assertThat(start).as("%s %s exists", tag, id).isGreaterThanOrEqualTo(0);
        int contentStart = xml.indexOf('>', start) + 1;
        int end = xml.indexOf("</" + tag + ">", contentStart);
        return xml.substring(contentStart, end);
    }

    private List<String> selectIdsContainingForUpdate(String xml) {
        Matcher matcher = Pattern.compile(
                "<select\\s+id=\"([^\"]+)\"[^>]*>(.*?)</select>",
                Pattern.DOTALL).matcher(xml);
        List<String> ids = new ArrayList<>();
        while (matcher.find()) {
            if (matcher.group(2).contains("FOR UPDATE")) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }
}
