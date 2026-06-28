package com.armada.marketing;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V014 营销任务数据模型验证:确认任务主表、执行目标、发送尝试、账号群基线快照均按 armada
 * BIGINT 时间列 + TINYINT 状态列 + tenant_id 隔离口径建成。
 */
class MarketingTaskDataModelMigrationDbTest extends DbTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v014_createsMarketingTaskTablesAndAccountBaselineTable() {
        assertThat(tableExists("marketing_task")).isTrue();
        assertThat(tableExists("marketing_task_target")).isTrue();
        assertThat(tableExists("marketing_task_send_attempt")).isTrue();
        assertThat(tableExists("account_group_baseline")).isTrue();
    }

    @Test
    void account_hasMarketingBaselineStateColumn() {
        assertThat(columnExists("account", "group_baseline_state")).isTrue();
        assertThat(columnType("account", "group_baseline_state")).isEqualTo("tinyint");
        assertThat(columnComment("account", "group_baseline_state"))
                .isEqualTo("营销树群基线状态:1=待拍 2=已拍 3=不启用过滤");
    }

    @Test
    void baselineTable_usesOneJsonSnapshotPerAccount() {
        assertThat(columnType("account_group_baseline", "baseline_group_jids")).isEqualTo("json");
        assertThat(columnType("account_group_baseline", "group_count")).isEqualTo("int");
        assertThat(columnType("account_group_baseline", "captured_at")).isEqualTo("bigint");
        assertThat(indexExists("account_group_baseline", "uq_account_baseline")).isTrue();
    }

    @Test
    void marketingTask_statusAndTimeColumnsMatchArmadaConventions() {
        assertThat(columnType("marketing_task", "status")).isEqualTo("tinyint");
        assertThat(columnType("marketing_task", "created_at")).isEqualTo("bigint");
        assertThat(columnType("marketing_task", "updated_at")).isEqualTo("bigint");
        assertThat(columnType("marketing_task", "deleted_at")).isEqualTo("bigint");
        assertThat(columnComment("marketing_task", "status"))
                .isEqualTo("任务状态:1=待启动/未发送 2=发送中 3=发送成功 4=发送失败 5=已停止 6=部分失败");
    }

    @Test
    void targetAndAttemptTables_haveExecutionIndexesAndStatusColumns() {
        assertThat(columnType("marketing_task_target", "status")).isEqualTo("tinyint");
        assertThat(columnType("marketing_task_target", "last_sent_at")).isEqualTo("bigint");
        assertThat(indexExists("marketing_task_target", "uq_marketing_task_target_pair")).isTrue();

        assertThat(columnType("marketing_task_send_attempt", "status")).isEqualTo("tinyint");
        assertThat(columnType("marketing_task_send_attempt", "attempted_at")).isEqualTo("bigint");
        assertThat(indexExists("marketing_task_send_attempt", "uq_marketing_task_attempt_no")).isTrue();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class,
                tableName,
                columnName);
        return count != null && count == 1;
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private String columnComment(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT column_comment FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class,
                tableName,
                indexName);
        return count != null && count > 0;
    }
}
