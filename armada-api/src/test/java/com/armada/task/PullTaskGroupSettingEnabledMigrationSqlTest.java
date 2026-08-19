package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 「群信息设置」总开关迁移的 SQL 合同测试。 */
class PullTaskGroupSettingEnabledMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V128__pull_task_group_setting_enabled.sql");

    /**
     * 存量任务一律置为关闭。
     *
     * <p>{@code NOT NULL DEFAULT 0} 让 MySQL 把已有行直接补成 0，不需要额外 UPDATE；
     * 一旦写成可空列或默认 1，存量任务会在毫无配置的情况下被判为「已启用」，执行时按空群名
     * 下发命令。</p>
     */
    @Test
    void existingTasksAreBackfilledAsDisabled() throws IOException {
        assertThat(sql())
                .contains("ADD COLUMN is_group_setting_enabled TINYINT(1) NOT NULL DEFAULT 0");
    }

    /** 列必须追加到表末尾，否则 MySQL 8.0 走不了 INSTANT ADD COLUMN，会重建整表。 */
    @Test
    void addsColumnAtTableTailForInstantAlter() throws IOException {
        assertThat(sql()).doesNotContain("ADD COLUMN is_group_setting_enabled TINYINT(1) "
                + "NOT NULL DEFAULT 0 AFTER");
    }

    private static String sql() throws IOException {
        assertThat(MIGRATION)
                .as("群信息设置总开关必须使用独立增量迁移")
                .exists();
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }
}
