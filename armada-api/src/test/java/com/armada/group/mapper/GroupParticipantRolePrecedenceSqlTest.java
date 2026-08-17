package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 账号轻量快照不得把更高可信来源确认的精确角色降级。
 *
 * <p>群组数据模型设计 §7.2 规定：账号 SUMMARY 的 self {@code admin} 只能在角色未知或同级低可信
 * 来源时写入，绝不能覆盖完整 metadata / 显式角色事件确认的 exact ADMIN/OWNER。
 * {@code groups_reported.admin} 是布尔值，最多表达管理员，天然无法表达群主
 * （test1 实测 {@code GROUP_SNAPSHOT} 来源的群主数恒为 0），因此一旦允许它按时间覆盖，
 * 完整成员快照确认的群主会被逐步冲成管理员甚至普通成员。</p>
 *
 * <p>原实现把来源可信度比较放在"事实时间完全相等"分支内，跨时间时只比时间不比来源，
 * 该缺陷在 PN/LID 双行并存时未暴露（账号快照只写 PN 行，够不到 LID 行的精确角色），
 * 但身份归并后两者会落到同一行，必须先补齐保护。</p>
 */
class GroupParticipantRolePrecedenceSqlTest {

    private static final String MAPPER = "AccountGroupCurrentSnapshotMapper.xml";

    @Test
    void legacyBackfilledMetadataRanksAboveAccountSummary() throws IOException {
        String xml = read(MAPPER);

        for (String tier : new String[] {"incomingRoleTier", "existingRoleTier"}) {
            assertThat(sqlFragment(xml, tier))
                    .as("回填自旧成员快照与群主事实的角色属于完整 metadata 级，必须高于账号轻量快照：" + tier)
                    .contains("'LEGACY_MEMBER_SNAPSHOT' THEN 2")
                    .contains("'LEGACY_METADATA_OWNER' THEN 2")
                    .contains("'GROUP_SNAPSHOT' THEN 1");
        }
    }

    @Test
    void lowerTrustSourceCannotDowngradeKnownRole() throws IOException {
        String fragment = sqlFragment(read(MAPPER), "incomingRoleWins");

        assertThat(fragment)
                .as("已有非未知角色时，低可信来源即使事实时间更晚也不得覆盖")
                .contains("NOT (")
                .contains("wa_group_participant.role &lt;&gt; 0");
    }

    private static String sqlFragment(String xml, String id) {
        String open = "<sql id=\"" + id + "\">";
        int start = xml.indexOf(open);
        assertThat(start).as("未找到 SQL 片段 " + id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</sql>", start);
        return xml.substring(start, end);
    }

    private static String read(String name) throws IOException {
        return Files.readString(
                Path.of("src/main/resources/mapper/group", name),
                StandardCharsets.UTF_8);
    }
}
