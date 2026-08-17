package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * V125 只补 {@code wa_group_participant} 的手机号反查索引。
 *
 * <p>该索引在群组数据模型设计 §5.4 的索引表中已定义，但 V120 建表时遗漏。缺索引时
 * 按 {@code (tenant_id, phone)} 反查只能退化为 tenant_id 前缀扫描（test1 实测扫描约
 * 26 万行），而按 phone 复用 metadata 已写入的 LID 成员行是账号快照不再新建 PN 行的前置条件。</p>
 */
class GroupParticipantPhoneIndexMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V125__group_participant_phone_index.sql");

    @Test
    void migrationAddsPhoneLookupIndex() throws IOException {
        assertThat(sql())
                .contains("idx_wa_group_participant_phone")
                .contains("(tenant_id, phone, group_id)");
    }

    @Test
    void migrationGuardsIndexExistenceForReentrantRun() throws IOException {
        assertThat(sql())
                .contains("information_schema.statistics")
                .contains("idx_wa_group_participant_phone");
    }

    @Test
    void migrationIsAdditiveIndexOnly() throws IOException {
        assertThat(sql())
                .doesNotContainIgnoringCase(
                        "CREATE TABLE",
                        "ADD COLUMN",
                        "DROP TABLE",
                        "DROP INDEX",
                        "INSERT INTO",
                        "UPDATE ",
                        "DELETE FROM");
    }

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }
}
