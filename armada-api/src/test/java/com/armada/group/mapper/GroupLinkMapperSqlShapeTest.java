package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.Test;

class GroupLinkMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/group/GroupLinkMapper.xml";

    @Test
    void accountObservedUpsertIsTenantQualifiedAndPreservesExistingOwnership() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
        String sourceSql = sqlBody(insertBlock(xml, "upsertAccountObservedGroup"));

        assertThat(sourceSql)
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("deleted_at = NULL")
                .contains("membership_state = CASE WHEN membership_state = 3 THEN 3 ELSE 2 END")
                .contains("WHEN sync_protocol_mask = 0 THEN #{row.syncProtocolMask}")
                .contains("WHEN sync_protocol_mask = #{row.syncProtocolMask} THEN sync_protocol_mask")
                .contains("ELSE 3")
                .doesNotContain("LAST_INSERT_ID")
                .doesNotContain("origin =")
                .doesNotContain("label_id =")
                .doesNotContain("import_batch_id =");

        String executableSql = sourceSql
                .replace("#{row.linkUrl}", "'wa://group/120363001@g.us'")
                .replace("#{row.groupName}", "'观察群'")
                .replace("#{row.origin}", "5")
                .replace("#{row.membershipState}", "2")
                .replace("#{row.syncProtocolMask}", "2")
                .replace("#{row.createdAt}", "1784966400000")
                .replace("#{row.updatedAt}", "1784966400000")
                .replace("#{observedGroupName}", "'观察群'");
        TenantLineInnerInterceptor interceptor = new TenantLineInnerInterceptor(() -> new LongValue(7L));

        String parsedSql = interceptor.parserSingle(executableSql, null);

        assertThat(parsedSql)
                .contains("tenant_id")
                .contains("1784966400000, 7)")
                .contains("ON DUPLICATE KEY UPDATE");
    }

    private static String insertBlock(String xml, String id) {
        String startTag = "<insert id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).as("mapper insert " + id + " exists").isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</insert>", start);
        assertThat(end).as("mapper insert " + id + " closes").isGreaterThan(start);
        return xml.substring(start, end);
    }

    private static String sqlBody(String mapperBlock) {
        int start = mapperBlock.indexOf('>');
        assertThat(start).isGreaterThanOrEqualTo(0);
        return mapperBlock.substring(start + 1)
                .replaceAll("(?s)<!--.*?-->", "")
                .trim();
    }
}
