package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** H2 测试基座自检：建表可用，且生成列 + 部分唯一索引语义与 MySQL 一致。 */
class PullTaskNormalLinkSchemaSelfTest {

    private final DataSource dataSource =
            PullTaskNormalLinkH2Support.dataSource("pull_task_schema_self_test");

    @BeforeEach
    void setUp() throws SQLException {
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @Test
    void allTenTablesAreCreated() throws SQLException {
        assertThat(PullTaskNormalLinkSchema.all()).hasSize(10);
        for (String table : new String[] {
                "pull_task", "pull_task_standard_setting", "pull_task_group_execution",
                "pull_task_material_member", "pull_task_group_account",
                "pull_task_account_action", "pull_task_pull_call",
                "pull_task_pull_call_member_attempt",
                "pull_task_pull_wave",
                "pull_task_standard_group_setting"}) {
            assertThat(countRows("SELECT COUNT(*) FROM " + table)).isZero();
        }
    }

    @Test
    void releasedPullerRowsDoNotBlockTheOccupancyUniqueKey() throws SQLException {
        // 已释放的拉手行 occupancy_key 为 NULL，不参与唯一约束，
        // 因此同一账号可以留下任意多条历史释放记录。
        insertGroupAccount(1, 10, 2, 500L, 900L);
        insertGroupAccount(2, 10, 2, 501L, 901L);

        assertThat(countRows(
                "SELECT COUNT(*) FROM pull_task_group_account WHERE occupancy_key IS NULL"))
                .isEqualTo(2);
    }

    @Test
    void secondActivePullerOccupancyOnSameAccountIsRejected() throws SQLException {
        insertGroupAccount(1, 10, 2, 500L, null);

        assertThatThrownBy(() -> insertGroupAccount(2, 10, 2, 501L, null))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void nonPullerRolesNeverOccupy() throws SQLException {
        // role_type=1 管理、role_type=3 站台的 occupancy_key 恒为 NULL，
        // 同一账号在不同执行行担任站台不受互斥限制。
        insertGroupAccount(1, 20, 3, 500L, null);
        insertGroupAccount(2, 20, 3, 501L, null);

        assertThat(countRows(
                "SELECT COUNT(*) FROM pull_task_group_account WHERE account_id = 20"))
                .isEqualTo(2);
    }

    @Test
    void draftExecutionRowsDoNotOccupyTheGroupLink() throws SQLException {
        // execution_status=0 草稿的 link_occupancy_key 为 NULL，
        // 两个用户的草稿可以同时贴同一条链接。
        insertExecution(1, 100, 0, "chat.whatsapp.com/AAA");
        insertExecution(2, 200, 0, "chat.whatsapp.com/AAA");

        assertThat(countRows(
                "SELECT COUNT(*) FROM pull_task_group_execution WHERE link_occupancy_key IS NULL"))
                .isEqualTo(2);
    }

    @Test
    void twoLiveTasksCannotHoldTheSameGroupLink() throws SQLException {
        insertExecution(1, 100, 1, "chat.whatsapp.com/AAA");

        assertThatThrownBy(() -> insertExecution(2, 200, 1, "chat.whatsapp.com/AAA"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void terminalExecutionRowReleasesTheGroupLink() throws SQLException {
        insertExecution(1, 100, 4, "chat.whatsapp.com/AAA");

        // execution_status=4 已完成，占用已释放，另一个任务可以接手同一条链接。
        insertExecution(2, 200, 1, "chat.whatsapp.com/AAA");
        assertThat(countRows("SELECT COUNT(*) FROM pull_task_group_execution")).isEqualTo(2);
    }

    private void insertGroupAccount(long id, long accountId, int roleType,
                                    long groupExecutionId, Long releasedAt) throws SQLException {
        execute("INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + " role_type, role_seq, created_at, updated_at, released_at) VALUES ("
                + id + ", 7, 1, " + groupExecutionId + ", " + accountId + ", '8613800000000', "
                + roleType + ", " + id + ", 100, 100, "
                + (releasedAt == null ? "NULL" : releasedAt) + ")");
    }

    private void insertExecution(long id, long taskId, int status, String link)
            throws SQLException {
        execute("INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + " source_link_line_no, source_file_index, source_file_name, "
                + " execution_status, created_at, updated_at) VALUES ("
                + id + ", 7, " + taskId + ", 1, '" + link + "', 'AAA', 1, 1, 'a.txt', "
                + status + ", 100, 100)");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long countRows(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
