package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 普通成员链接邀请权限在旧快照、当前快照与回填 SQL 中的双写契约。 */
class GroupMemberLinkModeMapperSqlTest {

    @Test
    void metadataWritersAndBackfillCarryIndependentMemberLinkMode() throws IOException {
        String legacy = read("GroupLinkPreviewMapper.xml");
        String current = read("AccountGroupCurrentSnapshotMapper.xml");
        String backfill = read("GroupModelBackfillMapper.xml");

        assertThat(legacy)
                .contains("member_add_mode, member_link_mode")
                .contains("#{memberLinkModeObserved} = TRUE")
                .contains("<update id=\"updateMemberLinkMode\"");
        assertThat(current)
                .contains("member_add_mode, member_link_mode")
                .contains("#{row.memberLinkModeObserved} = TRUE")
                .contains("THEN VALUES(member_link_mode)");
        assertThat(backfill)
                .contains("preview.member_link_mode")
                .contains("target.member_link_mode IS NULL")
                .contains("VALUES(member_link_mode)");
    }

    private static String read(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/resources/mapper/group", name),
                StandardCharsets.UTF_8);
    }
}
