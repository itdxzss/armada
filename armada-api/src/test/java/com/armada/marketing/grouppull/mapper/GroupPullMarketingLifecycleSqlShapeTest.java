package com.armada.marketing.grouppull.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 拉群营销生命周期 SQL 的状态条件测试。 */
class GroupPullMarketingLifecycleSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/GroupPullMarketingMapper.xml";

    @Test
    void lifecycleUpdatesUseExpectedStatusAndBusinessType() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

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

    private String block(String xml, String tag, String id) {
        String open = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(open);
        assertThat(start).as("%s %s exists", tag, id).isGreaterThanOrEqualTo(0);
        int contentStart = xml.indexOf('>', start) + 1;
        int end = xml.indexOf("</" + tag + ">", contentStart);
        return xml.substring(contentStart, end);
    }
}
