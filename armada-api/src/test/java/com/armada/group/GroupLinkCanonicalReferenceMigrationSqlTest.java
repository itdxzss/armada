package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 旧群链接句柄必须显式引用六表实体，不能继续靠旧预览字段运行时解析。 */
class GroupLinkCanonicalReferenceMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V123__group_link_canonical_references.sql");
    private static final Path CURRENT_SNAPSHOT_MAPPER = Path.of(
            "src/main/resources/mapper/group/AccountGroupCurrentSnapshotMapper.xml");

    @Test
    void addsTenantScopedCanonicalReferencesAndBackfillsBothTargets() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER TABLE group_link")
                .contains("ADD COLUMN group_id BIGINT DEFAULT NULL")
                .contains("ADD COLUMN group_invite_id BIGINT DEFAULT NULL")
                .contains("idx_group_link_group (tenant_id, group_id, id)")
                .contains("idx_group_link_invite (tenant_id, group_invite_id, id)")
                .contains("UPDATE group_link handle")
                .contains("preview.tenant_id = handle.tenant_id")
                .contains("current_group.tenant_id = handle.tenant_id")
                .contains("current_group.group_jid = LOWER(TRIM(preview.group_jid))")
                .contains("current_invite.tenant_id = handle.tenant_id")
                .contains("current_invite.invite_code = TRIM(preview.invite_code)")
                .contains("handle.group_id = current_group.id")
                .contains("handle.group_invite_id = current_invite.id")
                .doesNotContain("DELETE FROM", "DROP TABLE");
    }

    @Test
    void runtimeReferenceRepairOnlyClaimsUnmappedHandles() throws IOException {
        String xml = Files.readString(CURRENT_SNAPSHOT_MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("<update id=\"updateLegacyGroupReferences\">")
                .contains("AND handle.group_id IS NULL")
                .contains("<update id=\"updateSelectedLegacyGroupReferences\">")
                .contains("ORDER BY id ASC")
                .doesNotContain("handle.group_id IS NULL OR handle.group_id &lt;&gt; current_group.id");
    }
}
