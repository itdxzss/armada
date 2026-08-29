package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** MySQL 8 runtime fence 与 STOP 精确候选锁的静态 SQL 门禁。 */
class HyperlinkRuntimeFenceSqlShapeTest {

    @Test
    void dispatchUsesTenantScopedSharedFenceWhileCleanupUsesExclusiveFence() throws IOException {
        String xml = resource("/mapper/hyperlink/task/HyperlinkTaskRuntimeMapper.xml");
        String shared = statement(xml, "selectByTaskIdForShare", "selectByTaskIdForUpdate");
        String exclusive = statement(xml, "selectByTaskIdForUpdate", "transition");

        assertThat(shared)
                .contains("tenant_id=#{tenantId}", "hyperlink_task_id=#{taskId} FOR SHARE")
                .doesNotContain("FOR UPDATE");
        assertThat(exclusive)
                .contains("tenant_id=#{tenantId}", "hyperlink_task_id=#{taskId} FOR UPDATE");
    }

    @Test
    void stopCandidatesAreLockedBeforeExactIdCompareAndSet() throws IOException {
        String xml = resource("/mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml");
        String select = statement(xml, "lockUnsubmittedForStop", "deleteUnsubmitted");
        String update = statement(xml, "stopUnsubmittedByIds", "markProjected");

        assertThat(select).contains(
                "tenant_id=#{tenantId}",
                "command_id IS NULL AND send_status=1",
                "ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED");
        assertThat(update).contains(
                "fail_code='TASK_STOPPED'",
                "command_id IS NULL AND send_status=1",
                "<foreach collection=\"recipientIds\"");
    }

    @Test
    void metricsProjectionSelectsIdsBeforeExactRecipientLocks() throws IOException {
        String recipientXml = resource("/mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml");
        String candidates = statement(recipientXml,
                "selectMetricsProjectionCandidates", "lockMetricsProjectionBatch");
        String locked = statement(recipientXml, "lockMetricsProjectionBatch", "insertIgnoreBatch");
        String roundXml = resource("/mapper/hyperlink/task/HyperlinkTaskRoundMapper.xml");
        String rounds = statement(roundXml,
                "lockMetricsProjectionRounds", "rescheduleUnconsumedFirstRound");

        assertThat(candidates).contains(
                "WHERE needs_metrics_projection=1",
                "ORDER BY tenant_id, hyperlink_task_id, updated_at, id",
                "LIMIT #{limit}")
                .doesNotContain("FOR UPDATE", "GROUP BY");
        assertThat(locked).contains(
                "WHERE needs_metrics_projection=1 AND id IN",
                "collection=\"recipientIds\"",
                "FOR UPDATE SKIP LOCKED");
        assertThat(rounds).contains(
                "tenant_id=#{tenantId}", "hyperlink_task_id=#{taskId}",
                "collection=\"roundIds\"", "ORDER BY id FOR UPDATE");
    }

    @Test
    void dispatchLocksActiveRoundImmediatelyAfterTheRuntimeFence() throws IOException {
        String xml = resource("/mapper/hyperlink/task/HyperlinkTaskRoundMapper.xml");
        String select = statement(xml, "selectActiveForUpdate", "lockMetricsProjectionRounds");

        assertThat(select).contains(
                "tenant_id=#{tenantId}",
                "hyperlink_task_id=#{taskId}",
                "ORDER BY round_no DESC LIMIT 1 FOR UPDATE");
    }

    private String statement(String xml, String startId, String nextId) {
        int start = xml.indexOf("id=\"" + startId + "\"");
        int end = xml.indexOf("id=\"" + nextId + "\"", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start, end);
    }

    private String resource(String path) throws IOException {
        try (var input = HyperlinkRuntimeFenceSqlShapeTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
