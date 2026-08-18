package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 新群模式 V131~V134 迁移脚本的结构契约测试。
 *
 * <p>这些断言钉住的是「改了什么」和更重要的「没改什么」。新群模式的前提是不动既有
 * 群链接模式，因此任何对存量数据的写入、对唯一键与生成列的改动都必须被挡住。</p>
 */
class PullTaskNewGroupModeMigrationSqlTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path V131 = MIGRATION_DIR.resolve("V131__pull_task_new_group_mode_execution.sql");
    private static final Path V132 = MIGRATION_DIR.resolve("V132__pull_task_creation_mode.sql");
    private static final Path V133 = MIGRATION_DIR.resolve("V133__pull_task_entry_mode_group_create.sql");
    private static final Path V134 = MIGRATION_DIR.resolve("V134__pull_task_new_group_mode_setting.sql");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("V131 把链接三列改为可空，且原样保留 ascii_bin 排序规则")
    void v131MakesLinkColumnsNullableKeepingCollation() throws IOException {
        String sql = read(V131);

        // 新群模式在建群成功之前没有链接，三列必须允许为空。
        // MODIFY 会整列重写，不重复 CHARACTER SET/COLLATE 就会丢掉 ascii_bin，
        // 导致邀请码大小写敏感性失效。
        assertThat(sql)
                .contains("MODIFY COLUMN normalized_link VARCHAR(255) "
                        + "CHARACTER SET ascii COLLATE ascii_bin NULL")
                .contains("MODIFY COLUMN invite_code VARCHAR(64) "
                        + "CHARACTER SET ascii COLLATE ascii_bin NULL")
                .contains("MODIFY COLUMN source_link_line_no INT NULL");
    }

    @Test
    @DisplayName("V131 不碰唯一键和链接占用生成列")
    void v131LeavesUniqueKeysAndGeneratedColumnAlone() throws IOException {
        String sql = read(V131);

        // MySQL 的唯一索引不约束 NULL，normalized_link 为空时生成列结果也是 NULL，
        // 占用键自然不生效。因此三列改可空之后，唯一键与生成列一行都不需要动。
        // 这里断言的是「没有针对它们的 DDL 动作」，而不是「没提到它们」——
        // 脚本注释需要能解释为什么不动，不该被断言堵住。
        assertThat(sql)
                .doesNotContainIgnoringCase("GENERATED ALWAYS")
                .doesNotContainIgnoringCase("DROP INDEX")
                .doesNotContainIgnoringCase("DROP KEY")
                .doesNotContainIgnoringCase("ADD UNIQUE")
                .doesNotContainIgnoringCase("ADD INDEX")
                .doesNotContainIgnoringCase("DROP COLUMN");
    }

    @Test
    @DisplayName("V131 新增建群阶段所需的四列")
    void v131AddsGroupCreateColumns() throws IOException {
        String sql = read(V131);

        assertThat(sql)
                .contains("ADD COLUMN create_step TINYINT")
                .contains("ADD COLUMN create_operation_id VARCHAR(64)")
                .contains("ADD COLUMN create_attempt_count INT NOT NULL DEFAULT 0")
                .contains("ADD COLUMN group_subject VARCHAR(255)");
    }

    @Test
    @DisplayName("V131 把 stage 列注释订正为九个阶段")
    void v131CorrectsStageComment() throws IOException {
        String sql = read(V131);

        // 存量注释停留在 V093 时代的七阶段，缺 MANAGER_ADMIN 与 CLOSING，
        // 排查时按注释理解状态机会得出错误结论。
        assertThat(sql)
                .contains("1=链接校验")
                .contains("2=管理入群")
                .contains("3=管理员提权")
                .contains("4=管理拉手联系人")
                .contains("5=管理邀请拉手")
                .contains("6=拉人执行")
                .contains("7=料子提权")
                .contains("8=收口")
                .contains("9=建群");
    }

    @Test
    @DisplayName("V132 新增 creation_mode，默认值让存量行语义正确")
    void v132AddsCreationModeWithSafeDefault() throws IOException {
        String sql = read(V132);

        // 存量任务全部是群链接模式，默认值必须是 PASTED_LINK 而不是空，
        // 否则列表按模式筛选会漏掉全部历史任务。
        // 双单引号是因为整条 DDL 以字符串字面量嵌在 PREPARE 语句里，
        // SQL 里的单引号必须转义成两个。
        assertThat(sql)
                .contains("ADD COLUMN creation_mode VARCHAR(32) NOT NULL DEFAULT ''PASTED_LINK''")
                .contains("NEW_GROUP");
    }

    @Test
    @DisplayName("V132 不碰同表语义无关的 group_source 列")
    void v132DoesNotTouchGroupSource() throws IOException {
        String sql = read(V132);

        // group_source 是 V088 为拉群营销定义的历史群/自收群来源，
        // 名字相近但语义无关，误改会污染营销筛选。
        // 断言的是「没有针对它的 DDL 动作」——脚本注释需要能解释为什么不复用它。
        assertThat(sql).doesNotContain("COLUMN group_source");
    }

    @Test
    @DisplayName("V133 只把取值 4 写进 entry_mode 注释，不动列类型")
    void v133OnlyDocumentsNewEntryMode() throws IOException {
        String sql = read(V133);

        assertThat(sql)
                .contains("4=建群时作为初始成员加入")
                .contains("1=踩链接")
                .contains("2=管理员邀请")
                .contains("3=拉手拉入")
                .contains("MODIFY COLUMN entry_mode TINYINT")
                .doesNotContainIgnoringCase("ADD COLUMN")
                .doesNotContainIgnoringCase("ADD UNIQUE");
    }

    @Test
    @DisplayName("V134 只加三列真正缺的配置，站台初始数量默认 0 保持存量任务行为不变")
    void v134AddsOnlyTheThreeGenuinelyMissingSettings() throws IOException {
        String sql = read(V134);

        assertThat(sql)
                .contains("ADD COLUMN creator_group_id BIGINT")
                .contains("ADD COLUMN creator_group_name VARCHAR(100)")
                .contains("ADD COLUMN initial_station_count INT NOT NULL DEFAULT 0");
    }

    @Test
    @DisplayName("V134 不重复造 pull_task_standard_group_setting 已有的群配置列")
    void v134DoesNotDuplicateExistingGroupSettingColumns() throws IOException {
        String sql = read(V134);

        // 群名来源、手工群名、群头像、群描述、群设置执行时机，V095 建的
        // pull_task_standard_group_setting 已经全部具备。另起一套会让同一份配置有两个真相。
        // 断言的是「没有针对它们的 DDL 动作」——脚本注释需要能列出复用了哪些既有列。
        assertThat(sql)
                .doesNotContain("ADD COLUMN group_name")
                .doesNotContain("ADD COLUMN group_description")
                .doesNotContain("ADD COLUMN group_avatar")
                .doesNotContain("ADD COLUMN group_settings_timing")
                .doesNotContain("ADD COLUMN setting_timing");
    }

    @Test
    @DisplayName("四个迁移都是幂等的，且都不写业务数据")
    void allMigrationsAreIdempotentAndDataFree() throws IOException {
        for (Path migration : new Path[] {V131, V132, V133, V134}) {
            String sql = read(migration);

            // 共享 RDS 上迁移可能被重复执行，必须靠 information_schema 判断跳过。
            assertThat(sql)
                    .as("%s 必须是幂等脚本", migration.getFileName())
                    .contains("information_schema")
                    .contains("PREPARE")
                    .contains("EXECUTE")
                    .contains("DEALLOCATE PREPARE");

            assertThat(sql)
                    .as("%s 不得写业务数据", migration.getFileName())
                    .doesNotContainIgnoringCase("DELETE FROM")
                    .doesNotContainIgnoringCase("INSERT INTO")
                    .doesNotContainIgnoringCase("TRUNCATE")
                    .doesNotContainIgnoringCase("DROP TABLE");
        }
    }
}
