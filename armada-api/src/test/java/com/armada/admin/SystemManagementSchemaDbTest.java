package com.armada.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 系统管理 RBAC 真库结构与种子数据测试。 */
class SystemManagementSchemaDbTest extends DbTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsOnlyTheApprovedUserColumns() {
        assertThat(columnNames("sys_user")).containsExactlyInAnyOrder(
                "id", "tenant_id", "username", "nickname", "password_hash", "status",
                "created_at", "created_by", "updated_at", "updated_by");
    }

    @Test
    void seedsTenantAdminAndCurrentBusinessMenus() {
        assertThat(queryInt(
                "SELECT COUNT(*) FROM sys_role WHERE tenant_id=1 AND role_code='TENANT_ADMIN'"
                        + " AND status=1 AND is_system=1"))
                .isEqualTo(1);
        assertThat(queryInt(
                "SELECT COUNT(*) FROM sys_menu WHERE tenant_id=1 AND menu_key='BuyerChannel'"))
                .isEqualTo(1);
        assertThat(queryInt(
                "SELECT COUNT(*) FROM sys_menu WHERE tenant_id=1 AND menu_key='SystemUser'"))
                .isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM sys_menu WHERE menu_key='PermissionPage'"))
                .isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM sys_role_menu"))
                .isZero();
    }

    @Test
    void keepsOriginalMenusUnderTaskCenter() {
        assertThat(jdbc.queryForList("""
                SELECT child.menu_key
                FROM sys_menu child
                JOIN sys_menu parent
                  ON parent.tenant_id = child.tenant_id
                 AND parent.id = child.parent_id
                WHERE child.tenant_id = 1
                  AND parent.menu_key = 'TaskCenter'
                  AND child.status = 1
                ORDER BY child.sort_no, child.id
                """, String.class)).containsExactly(
                "AccountImport",
                "TaskGroupLinkImports",
                "GroupList",
                "HistoricalGroupManagement",
                "TaskPull",
                "TaskJoin",
                "TaskGroupMarketing",
                "TaskGroupPullMarketing",
                "TaskGroupCreationMarketing");
        assertThat(queryInt(
                "SELECT COUNT(*) FROM sys_menu WHERE tenant_id=1"
                        + " AND menu_key='GroupManagement' AND status=0"))
                .isEqualTo(1);
        assertThat(queryInt(
                "SELECT COUNT(*) FROM sys_menu WHERE tenant_id=1"
                        + " AND menu_key='ResourceManagement' AND menu_name='运营管理'"))
                .isEqualTo(1);
    }

    private List<String> columnNames(String tableName) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",
                String.class,
                tableName);
    }

    private int queryInt(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
