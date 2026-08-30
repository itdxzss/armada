package com.armada.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 账号申报类型、有效类型与协议校验状态的 Flyway 结构合同。 */
class AccountTypeVerificationMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V167__account_type_verification.sql");

    @Test
    void addsDistinctDeclaredAndVerificationFactsWithLegacyBackfill() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "declared_account_type",
                "account_type_verify_status",
                "account_type_verify_source",
                "account_type_verified_at",
                "business_verification_level",
                "business_verification_source",
                "business_verification_verified_at",
                "SET declared_account_type = account_type",
                "DEFAULT 4",
                "information_schema.COLUMNS");
    }
}
