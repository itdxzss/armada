package com.armada.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** WGP2 退出原因证据迁移测试，确保历史无证据 REMOVED 不会重新导出成被移出群组。 */
class GroupDepartureEvidenceMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V098_1__normalize_legacy_wgp2_removed.sql");

    @Test
    void normalizesOnlyLegacyWgp2RemovedFacts() throws IOException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:group_departure_evidence_migration;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE whatsapp_group_departed_member (
                    id BIGINT PRIMARY KEY,
                    source_type VARCHAR(32) NOT NULL,
                    exit_type VARCHAR(16) NOT NULL
                )
                """);
        jdbc.update("INSERT INTO whatsapp_group_departed_member VALUES (1, 'WGP2_NOTIFICATION', 'REMOVED')");
        jdbc.update("INSERT INTO whatsapp_group_departed_member VALUES (2, 'WGP2_NOTIFICATION', 'LEFT')");
        jdbc.update("INSERT INTO whatsapp_group_departed_member VALUES (3, 'HISTORY_SYNC', 'REMOVED')");

        jdbc.execute(Files.readString(MIGRATION, StandardCharsets.UTF_8));

        List<String> exitTypes = jdbc.query(
                "SELECT exit_type FROM whatsapp_group_departed_member ORDER BY id",
                (resultSet, rowNumber) -> resultSet.getString(1));
        assertThat(exitTypes).containsExactly("UNKNOWN", "LEFT", "REMOVED");
    }
}
