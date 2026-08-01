package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

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
    void mysqlCandidateQueryExpandsAndDeduplicatesHistoricalJidsBeforeJoining()
            throws Exception {
        BoundSql sql = boundSql(null, "countPageByTenant");

        assertThat(sql.getSql())
                .contains("JSON_TABLE")
                .contains("b0.tenant_id = ?")
                .contains("m.tenant_id = ?")
                .contains("MIN(baseline_group.group_jid) AS group_jid")
                .contains("BINARY baseline_group.group_jid")
                .contains("BINARY history.group_jid = BINARY m.group_jid")
                .doesNotContain("JSON_CONTAINS");
    }

    @Test
    void h2CandidateQueryKeepsEquivalentJsonContainsFallbackForRealMapperTests()
            throws Exception {
        BoundSql sql = boundSql("h2", "countPageByTenant");

        assertThat(sql.getSql())
                .contains("JSON_CONTAINS")
                .doesNotContain("JSON_TABLE");
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
        parameters.put("query", new PullTaskGroupMarketingCandidateQuery());
        parameters.put("offset", 0);
        parameters.put("limit", 10);
        parameters.put("groupJids", List.of("120363001@g.us"));
        return configuration.getMappedStatement(NAMESPACE + statementId)
                .getBoundSql(parameters);
    }
}
