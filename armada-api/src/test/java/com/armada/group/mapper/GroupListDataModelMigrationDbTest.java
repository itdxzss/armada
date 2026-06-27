package com.armada.group.mapper;

import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class GroupListDataModelMigrationDbTest extends DbTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void groupLink_hasOriginAndMembershipColumns() {
        assertThat(columnExists("group_link", "origin")).isTrue();
        assertThat(columnExists("group_link", "membership_state")).isTrue();
        assertThat(columnType("group_link", "origin")).isEqualTo("tinyint");
        assertThat(columnType("group_link", "membership_state")).isEqualTo("tinyint");
        assertThat(tableComment("group_link")).isEqualTo("群组入口主表(跨业务共享群组池)");
        assertThat(columnComment("group_link", "group_name")).isEqualTo("运营侧自定义群名称;导入链接不写;群组列表可编辑");
    }

    @Test
    void previewAndHealthTablesExist() {
        assertThat(tableExists("group_link_preview")).isTrue();
        assertThat(columnExists("group_link_preview", "group_jid")).isTrue();
        assertThat(columnExists("group_link_preview", "wa_subject")).isTrue();
        assertThat(tableExists("group_link_health")).isTrue();
        assertThat(columnExists("group_link_health", "health_status")).isTrue();
        assertThat(columnExists("group_link_health", "is_banned")).isTrue();
    }

    @Test
    void importBatch_usesDuplicateRowsInsteadOfSkippedRows() {
        assertThat(columnExists("group_link_import_batch", "duplicate_rows")).isTrue();
        assertThat(columnExists("group_link_import_batch", "skipped_rows")).isFalse();
    }

    @Test
    void importDetail_hasNewResultDimensions() {
        assertThat(columnExists("group_link_import_detail", "success_type")).isTrue();
        assertThat(columnExists("group_link_import_detail", "existing_origin")).isTrue();
    }

    @Test
    void importColumns_haveCurrentResultSemanticsComments() {
        assertThat(columnComment("group_link_import_batch", "inserted_rows")).isEqualTo("新增成功数量");
        assertThat(columnComment("group_link_import_batch", "adopted_rows")).isEqualTo("收编已有群入口的成功数量");
        assertThat(columnComment("group_link_import_batch", "failed_rows")).isEqualTo("失败总数(重复 + 格式错误)");
        assertThat(columnComment("group_link_import_detail", "group_name")).isEqualTo("群名称(保留旧列,导入链接不再写)");
        assertThat(columnComment("group_link_import_detail", "result")).isEqualTo("导入结果:1=成功 2=失败");
        assertThat(columnComment("group_link_import_detail", "fail_reason")).isEqualTo("失败原因:重复/格式错误;成功时为空");
        assertThat(columnComment("group_link_import_detail", "group_link_id")).isEqualTo("成功时关联group_link.id;失败时为空");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class,
                tableName,
                columnName);
        return count != null && count == 1;
    }

    private String tableComment(String tableName) {
        return jdbc.queryForObject(
                "SELECT table_comment FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                String.class,
                tableName);
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private String columnComment(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT column_comment FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }
}
