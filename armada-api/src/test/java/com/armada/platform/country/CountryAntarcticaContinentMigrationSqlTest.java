package com.armada.platform.country;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 南极地区国家主数据迁移脚本契约测试。 */
class CountryAntarcticaContinentMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V131__country_antarctica_continent.sql");

    @Test
    void migrationAssignsAntarcticaToAllPreviouslyUnclassifiedTerritories() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("SET continent_code = 'ANTARCTICA'")
                .contains("'AQ', 'BV', 'HM', 'TF'")
                .contains("deleted_at IS NULL")
                .contains("continent_code IS NULL");
    }
}
