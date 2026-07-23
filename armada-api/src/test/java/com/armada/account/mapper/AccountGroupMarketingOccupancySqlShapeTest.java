package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 账号分组营销整组占用 SQL 原子条件测试。 */
class AccountGroupMarketingOccupancySqlShapeTest {

    private static final String MAPPER_XML = "/mapper/account/AccountGroupMapper.xml";

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
    }

    private String updateBlock(String xml, String id) {
        String open = "<update id=\"" + id + "\">";
        int start = xml.indexOf(open);
        assertThat(start).as("update %s exists", id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</update>", start);
        assertThat(end).as("update %s closes", id).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
