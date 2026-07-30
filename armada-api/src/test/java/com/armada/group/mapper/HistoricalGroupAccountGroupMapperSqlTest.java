package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 账号组历史群分页 SQL 结构测试。 */
class HistoricalGroupAccountGroupMapperSqlTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/group/AccountGroupMembershipMapper.xml");

    @Test
    void accountGroupHistoryExpandsBaselineAndPaginatesInSql() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("countHistoricalGroupsByTenantAndAccountGroup")
                .contains("selectHistoricalGroupPageByTenantAndAccountGroup")
                .contains("JSON_TABLE")
                .contains("a.account_group_id = #{accountGroupId}")
                .contains("a.tenant_id = #{tenantId}")
                .contains("LIMIT #{offset}, #{pageSize}");
    }

    @Test
    void baselineOnlyDefinesHistoryRangeWhileRelationsCoverEveryAccountInTheGroup() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);
        String aggregation = xml.substring(
                xml.indexOf("<sql id=\"historicalGroupAggregated\">"),
                xml.indexOf("</sql>", xml.indexOf("<sql id=\"historicalGroupAggregated\">")));

        assertThat(aggregation)
                .contains("a0.account_group_id = #{accountGroupId}")
                .contains("a1.account_group_id = #{accountGroupId}")
                .contains("relation.group_jid = history.group_jid")
                .doesNotContain("JSON_CONTAINS");
        assertThat(xml)
                .contains("selectHistoricalGroupAccountPhonesByTenantAndAccountGroup")
                .contains("baseline_phone.group_jid")
                .contains("current_phone.group_jid");
    }

    @Test
    void accountGroupHistoryKeepsUnknownGroupsButExcludesKnownMemberOnlyGroups() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("knownMembershipCount")
                .contains("adminInGroup")
                .contains("knownMembershipCount = 0 OR adminInGroup = 1");
    }

    @Test
    void operationSelectorRequiresSameAccountGroupOnlineAdmin() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("existsHistoricalGroupByTenantAndAccountGroup")
                .contains("selectHistoricalGroupExecutionAccountByTenant")
                .contains("a.account_group_id = #{accountGroupId}")
                .contains("m.is_admin = 1")
                .contains("m.membership_status = #{inGroupStatus}")
                .contains("s.login_state = #{onlineLoginState}")
                .contains("s.account_state = #{normalAccountState}");
    }

    @Test
    void mysqlSpecificHistoryStatementsBuildBoundSqlWithNamedStatusConstants() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Files.newInputStream(MAPPER)) {
            XMLMapperBuilder builder = new XMLMapperBuilder(
                    input,
                    configuration,
                    MAPPER.toString(),
                    configuration.getSqlFragments());
            builder.parse();
        }
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", 1L);
        parameters.put("accountGroupId", 12L);
        parameters.put("offset", 0);
        parameters.put("pageSize", 20);
        parameters.put("groupJids", List.of("120363001@g.us"));

        BoundSql pageSql = configuration.getMappedStatement(
                        "com.armada.group.mapper.AccountGroupMembershipMapper."
                                + "selectHistoricalGroupPageByTenantAndAccountGroup")
                .getBoundSql(parameters);
        BoundSql phoneSql = configuration.getMappedStatement(
                        "com.armada.group.mapper.AccountGroupMembershipMapper."
                                + "selectHistoricalGroupAccountPhonesByTenantAndAccountGroup")
                .getBoundSql(parameters);

        assertThat(pageSql.getSql()).contains("relation.membership_status = ?");
        assertThat(phoneSql.getSql()).contains("current_phone.membership_status = ?");
    }
}
