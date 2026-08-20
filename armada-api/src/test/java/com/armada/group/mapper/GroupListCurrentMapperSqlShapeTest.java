package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.service.GroupExecutableAccountStates;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.enums.GroupListType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 新群当前事实列表查询必须 page-first，且不得重新全量聚合旧成员表。 */
class GroupListCurrentMapperSqlShapeTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/group/GroupListCurrentMapper.xml");

    @Test
    void listUsesLegacyHandlePageAndOnlyEnrichesCurrentPageFromSixTables() throws IOException {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("<select id=\"count\"")
                .contains("<select id=\"selectPage\"")
                .contains("WITH page_ids AS")
                .contains("LIMIT #{query.offset}, #{query.pageSize}")
                .contains("FROM page_ids page")
                .contains("wa_group current_group")
                .contains("wa_group_profile current_profile")
                .contains("wa_group_invite current_invite")
                .contains("wa_group_participant participant")
                .contains("execution_account.ws_phone = participant.phone")
                .contains("execution_state.login_state = 1")
                .contains("execution_state.account_state IN")
                .doesNotContain("execution_state.account_state = 2")
                .contains("current_group.id = handle.group_id")
                .contains("input_invite.id = handle.group_invite_id")
                .contains("current_group.id = page_handle.group_id")
                .contains("handle.tenant_id = #{tenantId}")
                .contains("participant.tenant_id = page_group.tenant_id")
                .contains("EXISTS (")
                .doesNotContain(
                        "whatsapp_group_member_snapshot",
                        "account_group_membership",
                        "group_link_health",
                        "FOR UPDATE");

        String countSql = statement(xml, "select", "count");
        assertThat(countSql).doesNotContain("GROUP_CONCAT", "page_ids");
        String detailMemberSql = statement(xml, "select", "selectGroupDetailMembers");
        assertThat(detailMemberSql)
                .contains("participant.last_snapshot_version = current_profile.member_snapshot_version")
                .contains("participant.presence_status = 1");
    }

    /**
     * 可用管理员的账号态口径必须与选号链路一致,含被抢登(6)与抢登中(7)。
     *
     * <p>这两态的号 login_state 仍可能在线、协议连接健康,判成不可用会让群组列表显示
     * "无可用管理员",同时邀请码任务因选不到号永久 DEFERRED,表现为邀请链接与状态恒空。
     * 三处判定(两个筛选分支 + 计数 CTE)必须同源,否则列表数字与筛选结果对不上。</p>
     */
    @Test
    void availableAdminAcceptsLoginReplacedAndTakingOverStates() throws IOException {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .as("三处判定共用同一个 bind，避免再次分叉")
                .contains("@com.armada.group.service.GroupExecutableAccountStates@executable()")
                .as("探针抽成共享片段，两个筛选分支不再各写一份")
                .contains("<sql id=\"availableAdminProbe\">")
                .as("不得回退为单值等值")
                .doesNotContain("account_state = 2");

        assertThat(GroupExecutableAccountStates.executable())
                .as("正常、被抢登、抢登中三态可执行")
                .containsExactly(
                        AccountStateCode.NORMAL,
                        AccountStateCode.LOGIN_REPLACED,
                        AccountStateCode.TAKING_OVER);
    }

    @Test
    void dynamicSqlRendersExistingFiltersForCountAndPage() throws IOException {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream(
                "/mapper/group/GroupListCurrentMapper.xml")) {
            new XMLMapperBuilder(
                    input,
                    configuration,
                    MAPPER.toString(),
                    configuration.getSqlFragments()).parse();
        }

        GroupLinkQuery query = new GroupLinkQuery();
        query.setLabelId(11L);
        query.setFolderId(12L);
        query.setGroupType(GroupListType.BOTH);
        query.setAvailableAdmin(false);
        query.setMemberCountMin(51);
        query.setMemberCountMax(500);
        query.setCountryIso2("PK");
        query.setContinentCode("ASIA");
        query.setAgeDaysMin(7);
        query.setAgeDaysMax(365);
        query.setSourceFileName("groups.csv");
        query.setOrigin(1);
        query.setMembershipState(2);
        query.setStatus("AVAILABLE");
        query.setKeyword("120363");
        query.setNowSeconds(1_800_000_000L);
        query.setPage(3);
        query.setPageSize(25);
        Map<String, Object> parameters = Map.of("tenantId", 7L, "query", query);

        String countSql = boundSql(configuration, "count", parameters);
        String pageSql = boundSql(configuration, "selectPage", parameters);

        for (String sql : java.util.List.of(countSql, pageSql)) {
            assertThat(sql)
                    .contains("handle.tenant_id = ?")
                    .contains("handle.label_id = ?")
                    .contains("COALESCE(current_group.folder_id, handle.folder_id) = ?")
                    .contains("handle.is_historical = 1")
                    .contains("handle.is_post_control = 1")
                    .contains("NOT EXISTS (")
                    .contains("current_profile.checked_member_count")
                    .contains("current_profile.member_count")
                    .contains("input_invite.checked_member_count")
                    .contains("legacy_preview.creator_country_iso2 = ?")
                    .contains("LEFT JOIN country filter_country")
                    .contains("COALESCE(legacy_preview.creator_continent_code, "
                            + "filter_country.continent_code) = ?")
                    .contains("legacy_preview.owner_phone LIKE CONCAT('%', ?, '%')")
                    .contains("current_profile.health_status")
                    .contains("input_invite.health_status")
                    .contains("LIKE CONCAT('%', ?, '%')")
                    .doesNotContain("owner_ranked", "FOR UPDATE");
        }
        assertThat(countSql).doesNotContain("WITH page_ids", "GROUP_CONCAT");
        assertThat(countSql)
                .contains("CAST(current_group.group_jid AS CHAR)")
                .contains("CAST(COALESCE(current_invite.invite_code, input_invite.invite_code) AS CHAR)")
                .contains("CAST(participant.phone AS CHAR)");
        assertThat(pageSql)
                .contains("WITH page_ids AS")
                .contains("COALESCE(legacy_preview.creator_continent_code, "
                        + "country.continent_code) AS creatorContinentCode")
                .contains("LIMIT ?, ?")
                .contains("FROM page_groups page_group")
                .contains("STRAIGHT_JOIN wa_group_participant participant")
                .contains("STRAIGHT_JOIN account controlled_account")
                .contains("LEFT JOIN account_state execution_state")
                .contains("GROUP_CONCAT")
                .contains("available_admin_count")
                .doesNotContain("page_preview.group_jid");

        GroupLinkQuery nonAsciiQuery = new GroupLinkQuery();
        nonAsciiQuery.setKeyword("五段号续批-909-2-20260806-095529");
        nonAsciiQuery.setPage(1);
        nonAsciiQuery.setPageSize(20);
        Map<String, Object> nonAsciiParameters = Map.of(
                "tenantId", 7L,
                "query", nonAsciiQuery);
        String nonAsciiCountSql = boundSql(configuration, "count", nonAsciiParameters);
        assertThat(nonAsciiCountSql)
                .contains("current_group.display_name")
                .doesNotContain("CAST(current_group.group_jid AS CHAR)")
                .doesNotContain("CAST(COALESCE(current_invite.invite_code, input_invite.invite_code) AS CHAR)")
                .doesNotContain("CAST(participant.phone AS CHAR)");
    }

    @Test
    void defaultCountAndPageIdQueryDoNotJoinEnrichmentTables() throws IOException {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream(
                "/mapper/group/GroupListCurrentMapper.xml")) {
            new XMLMapperBuilder(
                    input,
                    configuration,
                    MAPPER.toString(),
                    configuration.getSqlFragments()).parse();
        }

        GroupLinkQuery query = new GroupLinkQuery();
        query.setPage(1);
        query.setPageSize(20);
        Map<String, Object> parameters = Map.of("tenantId", 7L, "query", query);

        String countSql = boundSql(configuration, "count", parameters);
        String pageSql = boundSql(configuration, "selectPage", parameters);
        String pageIdSql = pageSql.substring(0, pageSql.indexOf("), page_groups AS"));

        assertThat(countSql)
                .contains("FROM group_link handle")
                .doesNotContain(
                        "group_link_preview",
                        "group_link_import_batch",
                        "wa_group current_group",
                        "wa_group_profile",
                        "wa_group_invite");
        assertThat(pageIdSql)
                .contains("FROM group_link handle")
                .doesNotContain(
                        "group_link_preview",
                        "group_link_import_batch",
                        "wa_group current_group",
                        "wa_group_profile",
                        "wa_group_invite");
    }

    private static String statement(String xml, String tag, String id) {
        String startTag = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</" + tag + ">", start);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start, end);
    }

    private static String boundSql(
            Configuration configuration,
            String statementId,
            Map<String, Object> parameters) {
        BoundSql boundSql = configuration
                .getMappedStatement(GroupListCurrentMapper.class.getName() + "." + statementId)
                .getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
