package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** V210 的真实列变更和历史数据回填；MySQL PREPARE 守卫只做结构验证。 */
class PullTaskIntervalRangeMigrationTest {

    @Test
    void addsOnlyMaximumAndBackfillsExistingFixedIntervalsWithoutChangingConfiguredRanges() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V210__pull_task_interval_range.sql"));
        assertThat(sql).contains("information_schema.columns", "column_name = 'pull_interval_max_seconds'");
        assertThat(sql).doesNotContain("ADD COLUMN pull_interval_min");
        Matcher ddl = Pattern.compile("'(ALTER TABLE [^\\n]+)', 'SELECT 1'").matcher(sql);
        assertThat(ddl.find()).isTrue();
        Matcher backfill = Pattern.compile("UPDATE pull_task_standard_setting[\\s\\S]*?;").matcher(sql);
        assertThat(backfill.find()).isTrue();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:pull_interval_v208;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pull_task_standard_setting (tenant_id BIGINT, task_id BIGINT, "
                    + "pull_interval_seconds INT NOT NULL, PRIMARY KEY(tenant_id,task_id))");
            statement.execute("INSERT INTO pull_task_standard_setting VALUES (7,1,30),(8,2,0),(7,3,10)");
            statement.execute(ddl.group(1).replace("''", "'"));
            statement.execute("UPDATE pull_task_standard_setting SET pull_interval_max_seconds=15 WHERE task_id=3");
            statement.execute(backfill.group());
            try (ResultSet rows = statement.executeQuery("SELECT pull_interval_seconds,pull_interval_max_seconds "
                    + "FROM pull_task_standard_setting ORDER BY task_id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(30);
                assertThat(rows.getInt(2)).isEqualTo(30);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(2)).isZero();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(10);
                assertThat(rows.getInt(2)).isEqualTo(15);
            }
        }
    }
}
