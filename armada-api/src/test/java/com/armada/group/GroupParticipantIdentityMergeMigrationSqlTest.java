package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * V126 把同群同号码的 PN/LID 双行归并为一行。
 *
 * <p>test1 实测双行形态 100% 统一（每组恰好 1 个 PN 行 + 1 个 LID 行，共 56928 组），
 * 因此只处理该形态；LID 是 Web 与 Android 协议共同认可的权威身份（两侧成员列表均只返回 LID），
 * 故保留 LID 行、回填 {@code pn_jid}、迁移账号绑定后删除 PN 行。</p>
 *
 * <p>语句顺序是正确性前提：账号绑定必须先改指 LID 行才能删 PN 行；而 {@code pn_jid} 必须等
 * PN 行删除之后才能回填，否则与 {@code uq_wa_group_participant_pn} 唯一键冲突。</p>
 */
class GroupParticipantIdentityMergeMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V126__group_participant_identity_merge.sql");

    @Test
    void migrationOnlyMergesOnePnPlusOneLidPairs() throws IOException {
        assertThat(sql())
                .as("只归并形态确定的配对，避免误并三行以上或同形态多行")
                .contains("HAVING COUNT(*) = 2")
                .contains("SUM(pn_jid IS NOT NULL) = 1")
                .contains("SUM(lid_jid IS NOT NULL) = 1")
                .as("phone 为空的成员无法按号码判定同一人，必须排除")
                .contains("phone IS NOT NULL");
    }

    @Test
    void bindingIsRepointedBeforePnRowDeletion() throws IOException {
        String sql = sql();
        int repoint = sql.indexOf("UPDATE wa_account_group_binding");
        int delete = sql.indexOf("DELETE");

        assertThat(repoint).as("缺少账号绑定改指语句").isGreaterThanOrEqualTo(0);
        assertThat(delete).as("缺少 PN 行删除语句").isGreaterThanOrEqualTo(0);
        assertThat(repoint)
                .as("账号绑定必须先改指 LID 行，否则删除 PN 行会留下悬空引用")
                .isLessThan(delete);
    }

    @Test
    void pnJidIsBackfilledAfterPnRowDeletion() throws IOException {
        String sql = sql();
        int delete = sql.indexOf("DELETE");
        int backfill = sql.lastIndexOf("SET lid.pn_jid");

        assertThat(backfill).as("缺少 pn_jid 回填语句").isGreaterThanOrEqualTo(0);
        assertThat(backfill)
                .as("pn_jid 必须在 PN 行删除后回填，否则与 uq_wa_group_participant_pn 冲突")
                .isGreaterThan(delete);
    }

    @Test
    void roleMergeFollowsSourceTrustTier() throws IOException {
        assertThat(sql())
                .as("角色归并必须复用 §7.2 来源可信度分级，回填来源属完整 metadata 级")
                .contains("'LEGACY_MEMBER_SNAPSHOT' THEN 2")
                .contains("'LEGACY_METADATA_OWNER' THEN 2")
                .contains("'GROUP_SNAPSHOT' THEN 1");
    }

    @Test
    void mergeMappingTableIsIndexedBeforeJoinWrites() throws IOException {
        String sql = sql();
        int addIndex = sql.indexOf("ADD INDEX idx_tmp_merge_pn");
        int firstJoinWrite = sql.indexOf("UPDATE wa_account_group_binding");

        assertThat(addIndex)
                .as("映射表必须建索引：CREATE TABLE ... AS SELECT 不产生索引，"
                        + "test1 实测未加索引时绑定改指单条 UPDATE 运行 47 分钟并阻塞实时写入")
                .isGreaterThanOrEqualTo(0);
        assertThat(sql).contains("ADD INDEX idx_tmp_merge_lid");
        assertThat(addIndex)
                .as("索引必须在所有 JOIN 写入之前建立")
                .isLessThan(firstJoinWrite);
    }

    @Test
    void migrationNeverTouchesRowsOutsideMergePairs() throws IOException {
        String sql = sql();

        assertThat(sql)
                .as("所有写操作都必须经配对映射约束，禁止无条件全表写")
                .doesNotContain("DELETE FROM wa_group_participant;")
                .doesNotContain("UPDATE wa_group_participant SET")
                .doesNotContain("TRUNCATE")
                .doesNotContain("DROP TABLE wa_group_participant");
    }

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }
}
