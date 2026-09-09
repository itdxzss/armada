package com.armada.contact.task;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** MySQL 专有迁移结构检查；实际读写与租户约束由 H2 Mapper 测试覆盖。 */
class ContactTaskLidMigrationSqlTest {
    @Test void extendsExistingAggregatesWithoutDroppingContactData() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V181__contact_lid_hyperlink.sql"));
        assertThat(sql).doesNotContain("CREATE TABLE", "DELETE FROM", "DROP TABLE");
        assertThat(sql).contains("MODIFY contact_phone VARCHAR(32) NULL",
                "(tenant_id, account_id, contact_jid)",
                "(tenant_id, task_id, task_account_id, contact_jid)",
                "(tenant_id, command_id)", "UNKNOWN SKIPPED");
        for (String column : new String[]{"delivered_at", "read_at", "stop_reason"}) {
            assertThat(sql).contains("column_name = '" + column + "'", "ADD COLUMN " + column);
        }
        assertThat(sql.indexOf("ADD UNIQUE KEY uq_account_contact_jid"))
                .isLessThan(sql.indexOf("DROP INDEX uq_account_contact'"));
    }
}
