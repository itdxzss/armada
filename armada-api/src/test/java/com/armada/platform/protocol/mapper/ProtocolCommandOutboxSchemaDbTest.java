package com.armada.platform.protocol.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ProtocolCommandOutboxSchemaDbTest extends DbTestBase {

    private static final String TABLE = "protocol_command_outbox";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void protocolCommandOutbox_hasCoreCommandColumns() {
        assertThat(tableExists(TABLE)).isTrue();
        assertThat(tableComment(TABLE)).isEqualTo("协议命令Outbox(Armada到协议层Kafka命令)");

        assertColumn("id", "bigint", "NO");
        assertColumn("tenant_id", "bigint", "NO");
        assertColumn("command_id", "varchar", "NO");
        assertColumn("batch_id", "varchar", "YES");
        assertColumn("command_type", "varchar", "NO");
        assertColumn("aggregate_type", "varchar", "NO");
        assertColumn("aggregate_id", "bigint", "NO");
        assertColumn("kafka_topic", "varchar", "NO");
        assertColumn("kafka_key", "varchar", "NO");
        assertColumn("protocol_account_id", "varchar", "NO");
        assertColumn("payload_json", "json", "NO");
        assertColumn("status", "tinyint", "NO");
        assertColumn("retry_count", "int", "NO");
        assertColumn("next_retry_at", "bigint", "NO");
        assertColumn("locked_by", "varchar", "YES");
        assertColumn("locked_at", "bigint", "YES");
        assertColumn("sent_at", "bigint", "YES");
        assertColumn("last_error", "varchar", "YES");
        assertColumn("created_at", "bigint", "NO");
        assertColumn("updated_at", "bigint", "NO");
        assertColumn("deleted_at", "bigint", "YES");
    }

    @Test
    void protocolCommandOutbox_hasDispatchAndLookupIndexes() {
        assertIndex("uk_command_id", true, List.of("command_id"));
        assertIndex("idx_dispatch", false, List.of("status", "next_retry_at", "id"));
        assertIndex("idx_batch", false, List.of("tenant_id", "batch_id"));
        assertIndex("idx_account", false, List.of("tenant_id", "aggregate_id", "created_at"));
    }

    @Test
    void protocolCommandOutbox_doesNotDuplicateSensitiveCredentialOrProxySecrets() {
        assertThat(columnExists(TABLE, "credential_json")).isFalse();
        assertThat(columnExists(TABLE, "creds_json")).isFalse();
        assertThat(columnExists(TABLE, "proxy_password")).isFalse();
        assertThat(columnExists(TABLE, "proxy_username")).isFalse();
    }

    private void assertColumn(String columnName, String dataType, String nullable) {
        assertThat(columnExists(TABLE, columnName))
                .as("%s.%s should exist", TABLE, columnName)
                .isTrue();
        assertThat(columnDataType(TABLE, columnName))
                .as("%s.%s data_type", TABLE, columnName)
                .isEqualTo(dataType);
        assertThat(columnNullable(TABLE, columnName))
                .as("%s.%s nullable", TABLE, columnName)
                .isEqualTo(nullable);
    }

    private void assertIndex(String indexName, boolean unique, List<String> columns) {
        List<IndexColumn> actual = jdbc.query(
                "SELECT non_unique, column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? "
                        + "ORDER BY seq_in_index",
                (rs, rowNum) -> new IndexColumn(rs.getInt("non_unique"), rs.getString("column_name")),
                TABLE,
                indexName);

        assertThat(actual)
                .as("%s index columns", indexName)
                .extracting(IndexColumn::columnName)
                .containsExactlyElementsOf(columns);
        assertThat(actual)
                .as("%s should have rows", indexName)
                .isNotEmpty()
                .allMatch(column -> unique ? column.nonUnique() == 0 : column.nonUnique() == 1);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private String tableComment(String tableName) {
        return jdbc.queryForObject(
                "SELECT table_comment FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                String.class,
                tableName);
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

    private String columnDataType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private String columnNullable(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private record IndexColumn(int nonUnique, String columnName) {
    }
}
