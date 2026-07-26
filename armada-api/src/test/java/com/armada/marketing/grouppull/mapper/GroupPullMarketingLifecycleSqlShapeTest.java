package com.armada.marketing.grouppull.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
                .doesNotContain("current_stage = 11");
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
}
