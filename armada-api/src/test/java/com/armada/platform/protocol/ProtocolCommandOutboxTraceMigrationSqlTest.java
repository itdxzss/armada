package com.armada.platform.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 协议命令 Outbox 追踪列 Flyway 脚本契约测试。 */
class ProtocolCommandOutboxTraceMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V111__add_trace_id_to_protocol_command_outbox.sql");

    @Test
    void migrationAddsOnlyNullableUnindexedTraceColumn() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("table_name = 'protocol_command_outbox'")
                .contains("column_name = 'trace_id'")
                .contains("ADD COLUMN trace_id VARCHAR(32) NULL")
                .contains("COMMENT ''全链路追踪标识''")
                .doesNotContain("CREATE INDEX")
                .doesNotContain("UPDATE protocol_command_outbox");
    }
}
