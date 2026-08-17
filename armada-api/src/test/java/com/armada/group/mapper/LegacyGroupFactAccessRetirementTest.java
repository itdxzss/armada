package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 防止已退役的群事实表被重新接回在线 Mapper。 */
class LegacyGroupFactAccessRetirementTest {

    private static final Path MAPPER_ROOT = Path.of("src/main/resources/mapper");
    private static final String MANUAL_BACKFILL = "group/GroupModelBackfillMapper.xml";
    private static final List<String> RETIRED_FACT_TABLES = List.of(
            "group_link_health",
            "account_group_membership",
            "account_group_baseline",
            "whatsapp_group_member_snapshot",
            "whatsapp_group_member_cache",
            "whatsapp_group_member_state",
            "whatsapp_group_member_join_fact",
            "whatsapp_group_departed_member");
    private static final Set<String> PREVIEW_COMPATIBILITY_ALLOWLIST = Set.of(
            "group/GroupLinkPreviewMapper.xml",
            "group/GroupListCurrentMapper.xml",
            "group/AccountGroupMembershipMapper.xml",
            "task/PullTaskGroupMarketingCandidateMapper.xml");

    @Test
    void onlineMappersDoNotReadOrWriteRetiredFactTables() throws IOException {
        for (Path mapper : mapperFiles()) {
            String relative = relative(mapper);
            if (MANUAL_BACKFILL.equals(relative)) {
                continue;
            }
            String sql = Files.readString(mapper).toLowerCase(Locale.ROOT);
            for (String table : RETIRED_FACT_TABLES) {
                assertThat(sql)
                        .as("online mapper %s must not access %s", relative, table)
                        .doesNotContain(table);
            }
        }
    }

    @Test
    void legacyPreviewAccessIsLimitedToDocumentedCompatibilityPaths() throws IOException {
        for (Path mapper : mapperFiles()) {
            String relative = relative(mapper);
            if (MANUAL_BACKFILL.equals(relative)) {
                continue;
            }
            String sql = Files.readString(mapper).toLowerCase(Locale.ROOT);
            if (sql.contains("group_link_preview")) {
                assertThat(PREVIEW_COMPATIBILITY_ALLOWLIST)
                        .as("unexpected group_link_preview access from %s", relative)
                        .contains(relative);
            }
        }
    }

    @Test
    void legacyPreviewWriterOnlyCarriesCreatorCompatibilityFields() throws IOException {
        String sql = Files.readString(
                MAPPER_ROOT.resolve("group/GroupLinkPreviewMapper.xml"))
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("upsertcreatorcompatibility")
                .contains("owner_phone")
                .contains("creator_country_iso2")
                .contains("creator_continent_code")
                .doesNotContain("invite_code")
                .doesNotContain("wa_subject")
                .doesNotContain("member_size")
                .doesNotContain("avatar_url")
                .doesNotContain("announce_only")
                .doesNotContain("member_add_mode")
                .doesNotContain("member_link_mode");
    }

    private static List<Path> mapperFiles() throws IOException {
        assertThat(MAPPER_ROOT).exists().isDirectory();
        try (var paths = Files.walk(MAPPER_ROOT)) {
            return paths.filter(path -> path.getFileName().toString().endsWith("Mapper.xml"))
                    .sorted()
                    .toList();
        }
    }

    private static String relative(Path path) {
        return MAPPER_ROOT.relativize(path).toString().replace('\\', '/');
    }
}
