package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 管理员设置阶段的 Flyway 与持久化枚举合同测试。 */
class PullTaskManagerAdminStageMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V101__pull_task_manager_admin_stage.sql");

    @Test
    void addsRetryFactsAndRewindsOnlyActiveManagersNeedingPromotion() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .replace("''", "'");

        assertThat(sql)
                .contains("ADD COLUMN attempt_no INT NOT NULL DEFAULT 0")
                .contains("ADD COLUMN retryable TINYINT(1) DEFAULT NULL")
                .contains("WHEN stage BETWEEN 3 AND 7 THEN stage + 1")
                .contains("manager_row.membership_status = 2")
                .contains("COALESCE(manager_row.admin_status, 0) <> 3")
                .contains("execution_row.execution_status IN (1, 2, 3)")
                .contains("task_row.status NOT IN ('COMPLETED', 'ENDED')")
                .contains("wait_resource_type = NULL")
                .contains("next_run_at = 0");
    }

    @Test
    void stageRenumberAndRewindAreCheckpointedForPartialRetry() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .replace("''", "'");

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS armada_schema_migration_checkpoint")
                .contains("migration_key = 'V101_pull_task_manager_admin_stage'")
                .contains("stage_renumbered = 0")
                .contains("manager_rewound = 0")
                .contains("START TRANSACTION")
                .contains("SET stage_renumbered = 1")
                .contains("SET manager_rewound = 1")
                .contains("COMMIT");
    }

    @Test
    void optionalDdlChecksTableExistenceBeforeAddingColumns() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'pull_task_account_action'")
                .contains("FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_account_action' AND column_name = 'attempt_no'")
                .contains("FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_account_action' AND column_name = 'retryable'");
    }

    @Test
    void javaEnumsMatchTheEightPersistedStages() {
        assertThat(PullTaskExecutionStage.values())
                .extracting(PullTaskExecutionStage::code)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(PullTaskExecutionStage.MANAGER_ADMIN.code()).isEqualTo(3);
        assertThat(PullTaskGroupAccountRole.PROMOTER.code()).isEqualTo(4);
        assertThat(PullTaskAccountActionType.PROMOTE_MANAGER.code()).isEqualTo(4);
    }
}
