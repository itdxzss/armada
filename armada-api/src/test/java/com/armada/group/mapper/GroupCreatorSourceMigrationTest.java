package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** MySQL 动态 DDL 的守卫做结构检查，真实 ALTER 和数据初始化在 H2 MySQL 模式执行。 */
class GroupCreatorSourceMigrationTest {
    @Test
    void migrationPreservesExistingPhonesAndMarksOnlyEmptyRowsUnknown() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V207__group_creator_phone_source.sql"));
        assertThat(migration).contains("information_schema.columns", "column_name = 'creator_phone_source'",
                "IF(@creator_source_missing", "PREPARE creator_source_stmt", "DEALLOCATE PREPARE creator_source_stmt");
        var ddl = Pattern.compile("'(ALTER TABLE[^\\n]+)',").matcher(migration);
        assertThat(ddl.find()).isTrue();
        String alter = ddl.group(1).replace("''", "'");
        String update = migration.substring(migration.indexOf("UPDATE group_link_preview"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:creator_migration;MODE=MySQL", "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE group_link_preview(id BIGINT, owner_phone VARCHAR(32), creator_country_iso2 VARCHAR(2))");
            statement.execute("INSERT INTO group_link_preview VALUES(1,'916375552817','IN'),(2,NULL,NULL),(3,' ',NULL)");
            statement.execute(alter);
            statement.execute(update);
            try (var rows = statement.executeQuery("SELECT * FROM group_link_preview ORDER BY id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("owner_phone")).isEqualTo("916375552817");
                assertThat(rows.getString("creator_country_iso2")).isEqualTo("IN");
                assertThat(rows.getInt("creator_phone_source")).isEqualTo(2);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("creator_phone_source")).isZero();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("creator_phone_source")).isZero();
            }
            statement.execute(update);
        }
    }

    @Test
    void upsertEvaluatesCountryBeforePhoneAndSourceUpgradeLast() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mapper/group/GroupLinkPreviewMapper.xml"));
        String updates = xml.substring(xml.indexOf("ON DUPLICATE KEY UPDATE"));
        assertThat(updates.indexOf("creator_country_iso2 =")).isLessThan(updates.indexOf("owner_phone ="));
        assertThat(updates.indexOf("creator_continent_code =")).isLessThan(updates.indexOf("owner_phone ="));
        assertThat(updates.indexOf("last_preview_at =")).isLessThan(updates.indexOf("owner_phone ="));
        assertThat(updates.indexOf("owner_phone =")).isLessThan(updates.indexOf("creator_phone_source = CASE"));
    }
}
