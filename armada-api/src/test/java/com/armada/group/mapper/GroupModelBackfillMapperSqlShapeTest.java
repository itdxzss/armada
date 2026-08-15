package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 群模型回填 MySQL 集合 SQL 的锁序和范围契约。 */
class GroupModelBackfillMapperSqlShapeTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/group/GroupModelBackfillMapper.xml");

    @Test
    void groupBackfillUsesTenantScopedSortedInsertWithoutGapLockUpdate() throws IOException {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertThat(xml)
                .contains("<select id=\"countInvalidGroupSources\"")
                .contains("<select id=\"countDuplicateGroupJids\"")
                .contains("GROUP BY preview.tenant_id, LOWER(TRIM(preview.group_jid))")
                .contains("HAVING COUNT(*) &gt; 1")
                .contains("<insert id=\"backfillGroups\"")
                .contains("INSERT INTO wa_group")
                .contains("preview.tenant_id = link.tenant_id")
                .contains("target.tenant_id = preview.tenant_id")
                .contains("GREATEST(link.updated_at, preview.updated_at) &gt;= target.updated_at")
                .contains("ORDER BY preview.tenant_id ASC, group_jid ASC")
                .contains("LIMIT #{limit}")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("VALUES(updated_at) &gt;= wa_group.updated_at")
                .contains("OR VALUES(created_at) &lt; wa_group.created_at")
                .doesNotContain("FOR UPDATE", "account_group_membership", "joined_at",
                        "first_post_control_observed_at");
    }
}
