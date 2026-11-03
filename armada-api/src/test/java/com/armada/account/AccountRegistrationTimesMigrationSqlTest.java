package com.armada.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 号质量分析时间列的 Flyway 结构合同:只加可空列、可重复执行、不回填。 */
class AccountRegistrationTimesMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V220__account_registration_times.sql");

    @Test
    void addsNullableRegistrationTimeColumnsIdempotently() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "ADD COLUMN wa_creation_at BIGINT NULL",
                "ADD COLUMN wa_business_member_since_at BIGINT NULL",
                "information_schema.COLUMNS");
        assertThat(sql).doesNotContain("UPDATE account", "DROP ", "NOT NULL");
    }
}
