package com.armada.account.contact;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 账号通讯录快照 Flyway 脚本契约测试。 */
class AccountContactMigrationSqlTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V157__account_contact_sync.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void createsBothTablesEachCarryingTenantColumn() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS account_contact")
                .contains("CREATE TABLE IF NOT EXISTS account_contact_sync");

        String[] parts = sql.split("CREATE TABLE IF NOT EXISTS account_contact_sync");
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).contains("tenant_id BIGINT NOT NULL");
        assertThat(parts[1]).contains("tenant_id BIGINT NOT NULL");
    }

    @Test
    void contactTableIsUniquePerAccountAndPhone() throws IOException {
        assertThat(sql())
                .contains("UNIQUE KEY uq_account_contact (tenant_id, account_id, contact_phone)")
                .contains("KEY idx_account_contact_named (tenant_id, account_id, is_named)")
                .contains("KEY idx_account_contact_sweep (tenant_id, account_id, synced_at)");
    }

    @Test
    void syncTableIsOneRowPerAccount() throws IOException {
        assertThat(sql())
                .contains("UNIQUE KEY uq_account_contact_sync (tenant_id, account_id)");
    }

    @Test
    void addsTwoIdempotentGuardedColumnsToAccountState() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("table_name = 'account_state'")
                .contains("column_name = 'contact_named_num'")
                .contains("column_name = 'contact_mutual_num'")
                .contains("ADD COLUMN contact_named_num INT NOT NULL DEFAULT 0")
                .contains("ADD COLUMN contact_mutual_num INT NOT NULL DEFAULT 0");
    }

    @Test
    void everyColumnDefinitionCarriesComment() throws IOException {
        java.util.List<String> uncommented = sql().lines()
                .map(String::trim)
                .filter(line -> line.matches("^[a-z_]+ (BIGINT|INT|VARCHAR|CHAR|TINYINT).*"))
                .filter(line -> !line.contains("COMMENT"))
                .toList();

        assertThat(uncommented).as("这些列缺 COMMENT").isEmpty();
    }
}
