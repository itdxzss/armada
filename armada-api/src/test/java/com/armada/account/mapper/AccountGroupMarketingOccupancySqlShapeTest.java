package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 账号分组营销整组占用 SQL 原子条件测试。 */
class AccountGroupMarketingOccupancySqlShapeTest {

    private static final String MAPPER_XML = "/mapper/account/AccountGroupMapper.xml";

    private static final String ACCOUNT_MAPPER_XML = "/mapper/account/AccountMapper.xml";

    @Test
    void lockAndReleaseUseSingleOwnerCheckedUpdates() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(updateBlock(xml, "tryLockMarketingOccupancy"))
                .contains("marketing_occupancy_task_id IS NULL")
                .contains("marketing_occupancy_type = #{occupancyType}")
                .contains("marketing_locked_at = #{now}");
        assertThat(updateBlock(xml, "releaseMarketingOccupancy"))
                .contains("marketing_occupancy_type = #{occupancyType}")
                .contains("marketing_occupancy_task_id = #{taskId}")
                .contains("marketing_occupancy_task_id = NULL");
        assertThat(updateBlock(xml, "softDeleteByIds"))
                .contains("marketing_occupancy_task_id IS NULL");
    }

    @Test
    void accountPageOnlyProjectsOccupancyFactsFromExistingGroupJoin() throws IOException {
        String xml = resource(ACCOUNT_MAPPER_XML);
        String selectPage = selectBlock(xml, "selectPage");

        assertThat(selectPage)
                .contains("g.marketing_occupancy_type AS marketingOccupancyType")
                .contains("g.marketing_occupancy_task_id AS marketingOccupancyTaskId")
                .contains("g.marketing_locked_at AS marketingLockedAt")
                .doesNotContain("group_pull_marketing_execution")
                .doesNotContain("group_pull_marketing_material")
                .doesNotContain("group_pull_marketing_account_stat")
                .doesNotContain("JOIN marketing_task")
                .doesNotContain("JOIN group_pull_marketing_task");
    }

    @Test
    void occupancyTaskStatusUsesOneBatchQuery() throws IOException {
        String xml = resource(MAPPER_XML);
        String select = selectBlock(xml, "selectMarketingOccupancyTasksByIds");

        assertThat(select)
                .contains("FROM marketing_task task")
                .contains("LEFT JOIN group_pull_marketing_task")
                .contains("AS occupancyOverrideType")
                .contains("task.id IN")
                .contains("collection=\"taskIds\"");
    }

    @Test
    void advancedOccupancyFilterResolvesGroupIdsOutsideAccountPage() throws IOException {
        String groupXml = resource(MAPPER_XML);
        String groupSelect = selectBlock(groupXml, "selectMarketingOccupancyGroupIds");
        String accountFilter = sqlBlock(resource(ACCOUNT_MAPPER_XML), "filter");

        assertThat(groupSelect)
                .contains("FROM account_group g")
                .contains("LEFT JOIN marketing_task task")
                .contains("LEFT JOIN group_pull_marketing_task")
                .contains("marketingOccupancyType")
                .contains("#{occupiedTaskKeyword}")
                .contains("#{occupiedBusinessType}");
        assertThat(accountFilter)
                .contains("resolvedOccupancyGroupIds")
                .contains("NOT EXISTS")
                .contains("marketing_account_occupancy")
                .doesNotContain("group_pull_marketing_execution")
                .doesNotContain("group_pull_marketing_material");
    }

    private String resource(String path) throws IOException {
        return new String(
                getClass().getResourceAsStream(path).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private String updateBlock(String xml, String id) {
        String open = "<update id=\"" + id + "\">";
        int start = xml.indexOf(open);
        assertThat(start).as("update %s exists", id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</update>", start);
        assertThat(end).as("update %s closes", id).isGreaterThan(start);
        return xml.substring(start, end);
    }

    private String selectBlock(String xml, String id) {
        String open = "<select id=\"" + id + "\"";
        int start = xml.indexOf(open);
        assertThat(start).as("select %s exists", id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</select>", start);
        assertThat(end).as("select %s closes", id).isGreaterThan(start);
        return xml.substring(start, end);
    }

    private String sqlBlock(String xml, String id) {
        String open = "<sql id=\"" + id + "\">";
        int start = xml.indexOf(open);
        assertThat(start).as("sql %s exists", id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</sql>", start);
        assertThat(end).as("sql %s closes", id).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
