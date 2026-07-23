package com.armada.marketing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 公共营销任务按业务类型隔离的 SQL 结构测试。 */
class MarketingBusinessTypeSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/MarketingTaskMapper.xml";

    @Test
    void ordinaryMenuAndLifecycleAreIsolatedFromGroupPullTasks() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("<result column=\"business_type\" property=\"businessType\"/>")
                .contains("id, tenant_id, task_name, business_type")
                .contains("task_name, business_type, account_group_id")
                .contains("#{taskName}, #{businessType}, #{accountGroupId}");
        assertThat(sqlBlock(xml, "sql", "TaskFilter")).contains("business_type = 1");
        assertThat(sqlBlock(xml, "select", "selectDueWaitingTasks")).contains("business_type = 1");
        assertThat(sqlBlock(xml, "update", "startPendingTask")).contains("business_type = 1");
        assertThat(sqlBlock(xml, "update", "pauseSendingTask")).contains("business_type = 1");
        assertThat(sqlBlock(xml, "update", "resumePausedTask")).contains("business_type = 1");
        assertThat(sqlBlock(xml, "update", "closeActiveTask")).contains("business_type = 1");
        assertThat(sqlBlock(xml, "select", "selectDueSendingTasks"))
                .contains("business_type IN (1, 2)");
    }

    private String sqlBlock(String xml, String tag, String id) {
        String open = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(open);
        assertThat(start).as("%s %s exists", tag, id).isGreaterThanOrEqualTo(0);
        int contentStart = xml.indexOf('>', start) + 1;
        int end = xml.indexOf("</" + tag + ">", contentStart);
        assertThat(end).as("%s %s closes", tag, id).isGreaterThan(contentStart);
        return xml.substring(contentStart, end);
    }
}
