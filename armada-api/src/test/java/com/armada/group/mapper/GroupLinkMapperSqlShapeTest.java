package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.Test;

/** 保留群入口 Mapper 的当前模型 SQL 结构门禁。 */
class GroupLinkMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/group/GroupLinkMapper.xml";

    @Test
    void accountObservedUpsertIsTenantQualifiedAndPreservesExistingOwnership() throws IOException {
        String sourceSql = sqlBody(insertBlock(mapperXml(), "upsertAccountObservedGroup"));
        assertThat(sourceSql)
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("deleted_at = NULL")
                .contains("membership_state = CASE WHEN membership_state = 3 THEN 3 ELSE 2 END")
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
        String parsed = new TenantLineInnerInterceptor(() -> new LongValue(7L))
                .parserSingle(executableSql, null);
        assertThat(parsed).contains("tenant_id").contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void activeGroupJidLookupUsesCanonicalGroupReference() throws IOException {
        String query = selectBlock(mapperXml(), "selectActiveIdByGroupJid");
        assertThat(query)
                .contains("LEFT JOIN wa_group current_group")
                .contains("current_group.id = link.group_id")
                .contains("current_group.group_jid = #{groupJid}")
                .contains("link.link_url = CONCAT('wa://group/', #{groupJid})")
                .doesNotContain("group_link_preview");
    }

    @Test
    void healthCheckCandidatesUseCurrentGroupAndProfileFacts() throws IOException {
        String query = selectBlock(mapperXml(), "selectHealthCheckCandidates");
        assertThat(query)
                .contains("INNER JOIN wa_group current_group")
                .contains("current_group.id = g.group_id")
                .contains("current_profile.banned IS NULL OR current_profile.banned = 0")
                .doesNotContain("group_link_preview")
                .doesNotContain("group_link_health");
    }

    @Test
    void legacyGroupLinkMapperHasNoClassificationWriter() throws IOException {
        assertThat(mapperXml())
                .doesNotContain("id=\"markHistorical\"")
                .doesNotContain("id=\"markPostControl\"")
                .doesNotContain("id=\"markClassifications\"")
                .doesNotContain("SET is_historical = 1")
                .doesNotContain("SET is_post_control = 1");
    }

    private String mapperXml() throws IOException {
        return new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static String insertBlock(String xml, String id) {
        int start = xml.indexOf("<insert id=\"" + id + "\"");
        int end = xml.indexOf("</insert>", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start, end);
    }

    private static String selectBlock(String xml, String id) {
        int start = xml.indexOf("<select id=\"" + id + "\"");
        int end = xml.indexOf("</select>", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start, end);
    }

    private static String sqlBody(String block) {
        return block.substring(block.indexOf('>') + 1)
                .replaceAll("(?s)<!--.*?-->", "")
                .trim();
    }
}
