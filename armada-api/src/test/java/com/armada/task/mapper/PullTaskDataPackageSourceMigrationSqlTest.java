package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MySQL information_schema/PREPARE 迁移守卫在 H2 外验证，真实列读写另由 Mapper 测试覆盖。 */
class PullTaskDataPackageSourceMigrationSqlTest {
    @Test
    void nullableSourcesAreGuardedAndIndexedWithoutChangingExistingMaterialStates() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V192__pull_task_group_data_package_source.sql"));
        for (String column : List.of("source_package_id", "source_package_generation",
                "source_package_phone_id", "source_allocation_version")) {
            assertThat(sql).contains("column_name = '" + column + "'");
            assertThat(sql).contains("ADD COLUMN " + column);
        }
        assertThat(sql).contains("idx_execution_package_source", "NULL COMMENT");
        assertThat(sql).doesNotContain("DROP TABLE", "UPDATE pull_task_material_member");
    }
}
