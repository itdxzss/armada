package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * V127 为 wa_group_profile 的 7 个可独立更新字段增加逐字段版本列。
 *
 * <p>整行水位 metadata_observed_at 无法承载多字段乱序 patch：改群名与改描述两个事件乱序到达时，
 * 后落库的描述会把整行水位推高，随后到达的稍早群名事件被整体判旧丢弃，群名永久停留在旧值。</p>
 *
 * <p>形态沿用 wa_group_participant 的 presence/role 模式（来源 + 事实时间两列），使决胜留在
 * upsert SQL 内、不引入行锁；逐字段 event_id 不落列以控制行宽。</p>
 */
class GroupProfileFieldVersionMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V127__group_profile_field_versions.sql");

    private static final String[] FIELDS = {
            "subject",
            "description",
            "announce_only",
            "admin_only_edit_info",
            "member_add_mode",
            "join_approval_mode",
            "ephemeral_duration_seconds"
    };

    @Test
    void everyPatchableFieldGetsSourceAndObservedAtColumns() throws IOException {
        String sql = sql();
        for (String field : FIELDS) {
            assertThat(sql)
                    .as("字段 %s 缺少来源列，同一事实时间无法按可信度决胜", field)
                    .contains("ADD COLUMN " + field + "_source");
            assertThat(sql)
                    .as("字段 %s 缺少事实时间列，无法与整行水位解耦", field)
                    .contains("ADD COLUMN " + field + "_observed_at");
        }
    }

    @Test
    void columnsAreAppendedWithoutAfterSoAlterCanStayInstant() throws IOException {
        assertThat(statements())
                .as("使用 AFTER 会让 MySQL 退化为 INPLACE/COPY 重建整表，必须追加到表末尾")
                .doesNotContain("AFTER ");
    }

    @Test
    void observedAtColumnsAreEpochMillisBigint() throws IOException {
        String sql = sql();
        for (String field : FIELDS) {
            assertThat(sql)
                    .as("字段 %s 的事实时间必须是 epoch 毫秒 BIGINT，与全站时间口径一致", field)
                    .contains(field + "_observed_at BIGINT");
        }
    }

    @Test
    void sourceColumnsUseAsciiBinForExactComparison() throws IOException {
        assertThat(sql())
                .as("来源列在 SQL 内按枚举名精确匹配分级，需大小写敏感的 ascii_bin")
                .contains("CHARACTER SET ascii COLLATE ascii_bin");
    }

    @Test
    void migrationOnlyTouchesGroupProfile() throws IOException {
        String sql = sql();
        int alterCount = sql.split("ALTER TABLE", -1).length - 1;
        assertThat(alterCount)
                .as("14 列应合并为一条 ALTER，只做一次 INSTANT 变更")
                .isEqualTo(1);
        assertThat(sql).contains("ALTER TABLE wa_group_profile");
    }

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    /**
     * 剥离 {@code --} 注释后的纯语句文本。
     *
     * <p>本迁移的注释里会解释"为什么不使用 AFTER"，直接对全文断言 doesNotContain 会被注释命中，
     * 因此禁止性断言必须只看真实语句。</p>
     */
    private static String statements() throws IOException {
        return sql().lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
