package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.sf.jsqlparser.expression.LongValue;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.enums.GroupListType;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
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

    @Test
    void groupListCountAndPageShareSnapshotBasedFilters() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
        String listSql = xml.substring(
                xml.indexOf("<sql id=\"groupListFrom\">"),
                xml.indexOf("<select id=\"selectActiveById\""));

        assertThat(listSql)
                .contains("<sql id=\"groupListFrom\">")
                .contains("<sql id=\"groupListFilter\">")
                .contains("<include refid=\"groupListFrom\"/>")
                .contains("<include refid=\"groupListFilter\"/>")
                .contains("whatsapp_group_member_snapshot")
                .contains("account_group_membership")
                .contains("FLOOR((#{nowSeconds} - p.group_created_at) / 86400)")
                .doesNotContain("FROM join_task_result");
    }

    @Test
    void groupListAdminAggregationUsesOneActiveControlledAccountJoin() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
        int groupListStart = xml.indexOf("<sql id=\"groupListFrom\">");
        int adminsEnd = xml.indexOf("    ) admins", groupListStart);
        String beforeAdminsEnd = xml.substring(groupListStart, adminsEnd);
        int adminsStart = groupListStart + beforeAdminsEnd.lastIndexOf("    LEFT JOIN (");
        String adminsSql = xml.substring(adminsStart, adminsEnd);

        assertThat(adminsSql)
                .contains("INNER JOIN account controlled_account")
                .contains("controlled_account.tenant_id = member.tenant_id")
                .contains("controlled_account.ws_phone = member.phone")
                .contains("controlled_account.deleted_at IS NULL")
                .doesNotContain("account_state")
                .doesNotContain("login_state")
                .doesNotContain("protocol_account_id")
                .doesNotContain("EXISTS");
    }

    @Test
    void historicalBackfillJsonTableAvoidsReservedGroupsAlias() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);
        int start = xml.indexOf("<select id=\"selectHistoricalClassificationBackfillCandidates\"");
        int end = xml.indexOf("</select>", start);
        String selectSql = xml.substring(start, end);

        assertThat(selectSql)
                .contains(") baseline_group")
                .contains("baseline_group.group_jid")
                .doesNotContain(") groups")
                .doesNotContain("groups.group_jid");
    }

    @Test
    void groupListDynamicSqlRendersCombinedFiltersForCountAndPage() throws IOException {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream(MAPPER_XML)) {
            new XMLMapperBuilder(
                    input,
                    configuration,
                    MAPPER_XML,
                    configuration.getSqlFragments()).parse();
        }
        GroupLinkQuery query = new GroupLinkQuery();
        query.setGroupType(GroupListType.BOTH);
        query.setAvailableAdmin(false);
        query.setMemberCountMin(51);
        query.setContinentCode("ASIA");
        query.setAgeDaysMax(365);
        query.setNowSeconds(1_800_000_000L);

        String countSql = normalized(configuration
                .getMappedStatement(GroupLinkMapper.class.getName() + ".countByLabel")
                .getBoundSql(query));
        String pageSql = normalized(configuration
                .getMappedStatement(GroupLinkMapper.class.getName() + ".selectPageByLabel")
                .getBoundSql(query));

        for (String sql : java.util.List.of(countSql, pageSql)) {
            assertThat(sql)
                    .contains("g.is_historical = 1")
                    .contains("g.is_post_control = 1")
                    .contains("COALESCE(operable.availableAdminCount, 0) = 0")
                    .contains("COALESCE(h.current_count, p.member_size) >= ?")
                    .contains("p.creator_continent_code = ?")
                    .contains("FLOOR((? - p.group_created_at) / 86400) <= ?");
        }
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

    private static String normalized(BoundSql boundSql) {
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
