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
            Path.of("src/main/resources/db/migration/V162__account_contact_sync.sql");

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
    void extendsAccountProfileInsteadOfAccountState() throws IOException {
        String sql = sql();

        // 计数落在上游的 account_profile（账号可筛选事实的统一落位），按它自己的约定
        // 带独立水位、NULL 表示未采集；绝不能写 0，0 和「不知道」在筛选里是两回事
        assertThat(sql)
                .contains("table_name = 'account_profile'")
                .contains("column_name = 'contact_named_num'")
                .contains("column_name = 'contact_named_synced_at'")
                .contains("ADD COLUMN contact_named_num INT DEFAULT NULL")
                .contains("ADD COLUMN contact_named_synced_at BIGINT DEFAULT NULL")
                .doesNotContain("account_state");
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
