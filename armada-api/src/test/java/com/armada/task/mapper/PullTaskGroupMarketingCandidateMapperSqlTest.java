package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.shared.security.DataScope;
import com.armada.task.model.dto.PullTaskGroupMarketingCandidateQuery;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 拉群营销候选群分页 SQL 结构测试。 */
class PullTaskGroupMarketingCandidateMapperSqlTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/task/PullTaskGroupMarketingCandidateMapper.xml");
    private static final String NAMESPACE =
            "com.armada.task.mapper.PullTaskGroupMarketingCandidateMapper.";

    @Test
    void candidateQueryUsesCurrentBindingsAndMigratedHistoricalFlag()
            throws Exception {
        BoundSql sql = boundSql(null, "countPageByTenant");

        assertThat(sql.getSql())
                .contains("FROM wa_account_group_binding binding")
                .contains("INNER JOIN wa_group_participant self_participant")
                .contains("self_participant.presence_status = 1")
                .contains("handle.is_historical = 1")
                .contains("LEFT JOIN wa_group_profile current_profile")
                .doesNotContain("account_group_membership")
                .doesNotContain("account_group_baseline")
                .doesNotContain("JSON_TABLE")
                .doesNotContain("JSON_CONTAINS")
                .doesNotContain("group_link_health");
    }

    @Test
    void candidateAccountsUseCurrentPresenceAndKeepLegacyCreatorCompatibility()
            throws Exception {
        BoundSql sql = boundSql(null, "selectAccountsByGroupLinkIds");

        assertThat(sql.getSql())
                .contains("FROM wa_account_group_binding binding")
                .contains("self_participant.presence_status = 1")
                .contains("self_participant.role IN (2, 3)")
                .contains("legacy_preview.owner_phone")
                .contains("binding.last_observed_at")
                .contains("a.owner_user_id = handle.owner_user_id")
                .doesNotContain("account_group_membership");
    }

    @Test
    void candidateQueryScopesHandlesAndOwnerMatchesJoinedFacts() throws Exception {
        BoundSql sql = boundSql(null, "selectPageByTenant");

        assertThat(sql.getSql())
                .contains("handle.owner_user_id = ?")
                .contains("a.owner_user_id = handle.owner_user_id")
                .contains("jr.owner_user_id = handle.owner_user_id")
                .contains("filter_handle.owner_user_id = handle.owner_user_id")
                .contains("fa.owner_user_id = filter_handle.owner_user_id")
                .contains("GROUP BY current_group.group_jid, handle.owner_user_id");
    }

    private static BoundSql boundSql(String databaseId, String statementId)
            throws Exception {
        Configuration configuration = new Configuration();
        configuration.setDatabaseId(databaseId);
        try (InputStream input = Files.newInputStream(MAPPER)) {
            XMLMapperBuilder builder = new XMLMapperBuilder(
                    input,
                    configuration,
                    MAPPER.toString(),
                    configuration.getSqlFragments());
            builder.parse();
        }
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", 7L);
        PullTaskGroupMarketingCandidateQuery query = new PullTaskGroupMarketingCandidateQuery();
        query.setManagerPhone("000001");
        parameters.put("query", query);
        parameters.put("offset", 0);
        parameters.put("limit", 10);
        parameters.put("groupJids", List.of("120363001@g.us"));
        parameters.put("groupLinkIds", List.of(1001L));
        parameters.put("scope", DataScope.self(88L));
        return configuration.getMappedStatement(NAMESPACE + statementId)
                .getBoundSql(parameters);
    }
}
