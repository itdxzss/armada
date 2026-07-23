package com.armada.marketing.grouppull.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 拉群营销资源分配 SQL 约束测试。 */
class GroupPullMarketingAllocatorSqlShapeTest {

    @Test
    void allocatorUsesFiveInflightLimitAndStableMarketerOrder() throws IOException {
        String xml = resource("/mapper/marketing/GroupPullMarketingMapper.xml");

        assertThat(block(xml, "select", "countInflightExecutions"))
                .contains("execution_status IN (1, 2)");
        assertThat(block(xml, "select", "selectBuilderCandidateForUpdate"))
                .contains("marketing_occupancy_task_id IS NULL")
                .contains("FOR UPDATE");
        assertThat(block(xml, "select", "selectMarketerCandidateForUpdate"))
                .contains("reserved_group_count")
                .contains("joined_group_count")
                .contains("ORDER BY a.created_at DESC")
                .contains("FOR UPDATE");
    }

    @Test
    void accountOccupancyKeepsGroupPullBuildersUntilResourcesReleased() throws IOException {
        String xml = resource("/mapper/marketing/MarketingAccountOccupancyMapper.xml");

        assertThat(xml)
                .contains("gp.resource_status IN (2, 3, 5)")
                .contains("mt.business_type = 1")
                .contains("mt.business_type = 2");
        assertThat(block(xml, "delete", "releaseByTemplateIds"))
                .contains("mt.business_type = 1");
        assertThat(block(xml, "delete", "releaseByTaskAndAccount"))
                .contains("marketing_task_id = #{taskId}")
                .contains("account_id = #{accountId}");
    }

    private String resource(String path) throws IOException {
        return new String(getClass().getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8);
    }

    private String block(String xml, String tag, String id) {
        int start = xml.indexOf("<" + tag + " id=\"" + id + "\"");
        assertThat(start).as("%s %s exists", tag, id).isGreaterThanOrEqualTo(0);
        int contentStart = xml.indexOf('>', start) + 1;
        int end = xml.indexOf("</" + tag + ">", contentStart);
        return xml.substring(contentStart, end);
    }
}
