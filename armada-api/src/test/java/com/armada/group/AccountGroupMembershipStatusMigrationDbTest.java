package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.testsupport.DbTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 账号群关系状态迁移真库结构测试。 */
class AccountGroupMembershipStatusMigrationDbTest extends DbTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void membershipTableHasCurrentStatusColumnsAndIndex() {
        assertColumn("membership_status", "tinyint", false);
        assertColumn("status_source", "varchar", true);
        assertColumn("status_updated_at", "bigint", false);
        assertColumn("last_exit_type", "tinyint", true);
        assertColumn("last_exited_at", "bigint", true);
        assertColumn("last_seen_at", "bigint", true);

        String columns = jdbc.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'account_group_membership'
                  AND index_name = 'idx_account_group_membership_status'
                """, String.class);
        assertThat(columns).isEqualTo("tenant_id,account_id,membership_status,deleted_at");
    }

    private void assertColumn(String columnName, String dataType, boolean nullable) {
        Map<String, Object> column = jdbc.queryForMap("""
                SELECT data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'account_group_membership'
                  AND column_name = ?
                """, columnName);
        assertThat(column.get("data_type")).isEqualTo(dataType);
        assertThat(column.get("is_nullable")).isEqualTo(nullable ? "YES" : "NO");
    }
}
