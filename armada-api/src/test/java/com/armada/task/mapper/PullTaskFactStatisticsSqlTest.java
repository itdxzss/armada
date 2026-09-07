package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 执行运维入口使用的原始 SQL，验证独立聚合、执行行过滤与显式租户关联。 */
class PullTaskFactStatisticsSqlTest {

    private JdbcTemplate jdbc;
    private String query;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = PullTaskNormalLinkH2Support.dataSource("pull_task_fact_statistics_test");
        jdbc = new JdbcTemplate(dataSource);
        query = Files.readString(Path.of("../docs/operations/pull-task-fact-statistics.sql"));
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            for (String sql : SCHEMA_AND_FIXTURE.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    @Test
    void aggregatesEachFactIndependentlyAndDoesNotIncludeOtherTenantRows() {
        Map<String, Object> row = jdbc.queryForMap(sql(1, null));

        assertCounts(row, 3, 1, 4, 2, 5, 1);
        assertThat(row.get("protocol_backends")).isEqualTo("ANDROID,WEB");
    }

    @Test
    void executionFilterAppliesToEveryFactAndCommandReference() {
        Map<String, Object> row = jdbc.queryForMap(sql(1, 12L));

        assertCounts(row, 1, 0, 1, 1, 1, 0);
        // 该执行行的命令仅命中其他租户的 Outbox，不能泄漏其后端。
        assertThat(row.get("protocol_backends")).isEqualTo("-");
    }

    @Test
    void emptyTaskAndMissingExecutionReturnZeroCounts() {
        for (String sql : new String[]{sql(2, null), sql(1, 999L)}) {
            Map<String, Object> row = jdbc.queryForMap(sql);
            assertCounts(row, 0, 0, 0, 0, 0, 0);
            assertThat(row.get("protocol_backends")).isEqualTo("-");
        }
    }

    @Test
    void missingDeletedAndOtherTaskModesHaveNoNormalLinkStatistics() {
        for (long taskId : new long[]{3, 4, 999}) {
            assertThat(jdbc.queryForList(sql(taskId, null))).isEmpty();
        }
    }

    private String sql(long taskId, Long executionId) {
        return query.replace("@task_id", Long.toString(taskId))
                .replace("@execution_id", executionId == null ? "NULL" : executionId.toString());
    }

    private void assertCounts(Map<String, Object> row, long... expected) {
        String[] columns = {"action_count", "action_command_count", "pull_call_count",
                "pull_command_count", "material_member_count", "unreleased_puller_count"};
        assertThat(row.get("record_type")).isEqualTo("FACTS");
        for (int i = 0; i < columns.length; i++) {
            assertThat(((Number) row.get(columns[i])).longValue()).as(columns[i]).isEqualTo(expected[i]);
        }
    }

    // 仅定义本只读投影涉及的真实列；MySQL 索引执行计划另在测试环境验证。
    private static final String SCHEMA_AND_FIXTURE = """
            CREATE TABLE pull_task (id BIGINT, tenant_id BIGINT, task_type VARCHAR(32),
                mode VARCHAR(32), deleted_at BIGINT);
            CREATE TABLE pull_task_group_execution (id BIGINT, tenant_id BIGINT, task_id BIGINT);
            CREATE TABLE pull_task_account_action (tenant_id BIGINT, group_execution_id BIGINT,
                command_id VARCHAR(64));
            CREATE TABLE pull_task_pull_call (tenant_id BIGINT, group_execution_id BIGINT,
                command_id VARCHAR(64));
            CREATE TABLE pull_task_material_member (tenant_id BIGINT, group_execution_id BIGINT,
                admin_command_id VARCHAR(64));
            CREATE TABLE pull_task_group_account (tenant_id BIGINT, group_execution_id BIGINT,
                role_type TINYINT, released_at BIGINT);
            CREATE TABLE protocol_command_outbox (tenant_id BIGINT, command_id VARCHAR(64),
                protocol_backend VARCHAR(32));
            INSERT INTO pull_task VALUES
                (1,7,'STANDARD','NORMAL_LINK',NULL), (2,7,'STANDARD','NORMAL_LINK',NULL),
                (3,7,'STANDARD','NORMAL_LINK',1), (4,7,'GROUP_MARKETING','GROUP_TRANSFER',NULL);
            INSERT INTO pull_task_group_execution VALUES (11,7,1), (12,7,1), (13,8,1);
            INSERT INTO pull_task_account_action VALUES
                (7,11,'shared'), (7,11,'shared'), (7,12,NULL), (8,11,'foreign'), (8,13,'foreign');
            INSERT INTO pull_task_pull_call VALUES
                (7,11,'shared'), (7,11,'shared'), (7,11,NULL), (7,12,'foreign'), (8,11,'foreign');
            INSERT INTO pull_task_material_member VALUES
                (7,11,'admin'), (7,11,'admin'), (7,11,'shared'), (7,11,NULL),
                (7,12,'foreign'), (8,11,'foreign');
            INSERT INTO pull_task_group_account VALUES
                (7,11,2,NULL), (7,11,2,1), (7,11,3,NULL), (8,11,2,NULL);
            INSERT INTO protocol_command_outbox VALUES
                (7,'shared','ANDROID'), (7,'admin','WEB'), (8,'foreign','FOREIGN');
            """;
}
