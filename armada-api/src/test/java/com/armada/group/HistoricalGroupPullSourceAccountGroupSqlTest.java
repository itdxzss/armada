package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 历史群拉人来源账号组迁移与 Mapper 合同测试。 */
class HistoricalGroupPullSourceAccountGroupSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V086__historical_group_pull_source_account_group.sql");

    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/group/HistoricalGroupPullExecutionMapper.xml");

    @Test
    void migrationAddsBackfillsAndIndexesSourceAccountGroup() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("information_schema.columns")
                .contains("source_account_group_id")
                .contains("operation_account.account_group_id")
                .contains("information_schema.statistics")
                .contains("idx_historical_pull_source_group");
    }

    @Test
    void mapperCreatesAndFindsLatestExecutionBySourceAccountGroup() throws Exception {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("source_account_group_id")
                .contains("#{sourceAccountGroupId}")
                .contains("source_account_group_id = #{sourceAccountGroupId}")
                .doesNotContain("operation_account_id = #{accountId}");
    }
}
