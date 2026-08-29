package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 锁定默认累计查询不扫描 recipient，且时间查询命中任务时间条件。 */
class HyperlinkAccountStatsSqlShapeTest {

    @Test
    void noTimeCountAndPageSqlNeverReferenceRecipient() throws IOException {
        String xml = resource("mapper/hyperlink/task/HyperlinkTaskAccountStatMapper.xml");
        String count = select(xml, "countAccountStats");
        String page = select(xml, "selectAccountStats");

        assertThat(count).contains("hyperlink_task_account_stat");
        assertThat(page).contains("hyperlink_task_account_stat", "LIMIT #{criteria.pageSize}");
        assertThat(count).doesNotContain("hyperlink_task_recipient");
        assertThat(page).doesNotContain("hyperlink_task_recipient");
    }

    @Test
    void timeSqlHasTaskAndHalfOpenSubmittedAtBounds() throws IOException {
        String xml = resource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml");
        String source = sql(xml, "AccountStatTimeSourceFilters");

        assertThat(source).contains(
                "r.hyperlink_task_id = #{criteria.taskId}",
                "r.submitted_at &gt;= #{criteria.startAt}",
                "r.submitted_at &lt; #{criteria.endAt}",
                "r.submitted_at &lt;= #{criteria.snapshotAt}");
        assertThat(xml).contains("GROUP BY r.account_id", "LIMIT #{criteria.pageSize}");
        assertThat(xml).doesNotContain("account_stat_hourly");
    }

    @Test
    void ordinaryMarketingWorkerCannotClaimHyperlinkExportJobs() throws IOException {
        String xml = resource("mapper/marketing/MarketingTaskExportMapper.xml");
        assertThat(select(xml, "selectProcessableJobs"))
                .contains("export_mode IN ('COUNTRY_ENTRY', 'FULL')");
        assertThat(element(xml, "update", "markExhaustedJobs"))
                .contains("export_mode IN ('COUNTRY_ENTRY', 'FULL')");
        assertThat(select(xml, "selectExpiredFiles"))
                .contains("export_mode IN ('COUNTRY_ENTRY', 'FULL')");
    }

    private static String resource(String name) throws IOException {
        try (var input = new ClassPathResource(name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String select(String xml, String id) {
        return element(xml, "select", id);
    }

    private static String sql(String xml, String id) {
        return element(xml, "sql", id);
    }

    private static String element(String xml, String tag, String id) {
        int start = xml.indexOf("<" + tag + " id=\"" + id + "\"");
        int end = xml.indexOf("</" + tag + ">", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
