package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 拉群群设置动作类型 V119 迁移脚本的结构契约测试。 */
class PullTaskGroupSettingsActionMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V119__pull_task_group_settings_action.sql");

    @Test
    @DisplayName("迁移把两个新动作类型写进列注释")
    void migrationDocumentsBothNewActionTypes() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        // 列注释是 action_type 取值的唯一权威说明，漏掉会让后续排查看不懂 5/6。
        assertThat(sql)
                .contains("5=放开加人权限")
                .contains("6=关闭进群审核");
    }

    @Test
    @DisplayName("迁移保留存量四种动作类型的注释")
    void migrationKeepsExistingActionTypeComments() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("1=保存联系人")
                .contains("2=邀请入群")
                .contains("3=踩链接入群")
                .contains("4=设置任务管理员");
    }

    @Test
    @DisplayName("迁移只改注释，不动列类型、索引和业务数据")
    void migrationOnlyChangesColumnComment() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        // action_type 已是 TINYINT NOT NULL，本次不扩容也不动唯一键；
        // 任何数据写入都可能破坏存量执行行。
        assertThat(sql)
                .contains("MODIFY COLUMN action_type TINYINT NOT NULL")
                .doesNotContainIgnoringCase("UPDATE ")
                .doesNotContainIgnoringCase("DELETE FROM")
                .doesNotContainIgnoringCase("INSERT INTO")
                .doesNotContainIgnoringCase("DROP ")
                .doesNotContainIgnoringCase("ADD COLUMN")
                .doesNotContainIgnoringCase("ADD INDEX")
                .doesNotContainIgnoringCase("ADD UNIQUE");
    }
}
