package com.armada.group;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 已执行的创建者手机号归属区历史迁移与当前运行时隔离契约测试。 */
class GroupCreatorPhoneRegionMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V129__group_creator_phone_region.sql");
    private static final Path LIST_MAPPER = Path.of(
            "src/main/resources/mapper/group/GroupListCurrentMapper.xml");
    private static final Path PREVIEW_MAPPER = Path.of(
            "src/main/resources/mapper/group/GroupLinkPreviewMapper.xml");

    @Test
    void migrationCreatesGlobalPrefixCatalogAddsSnapshotsAndBackfillsIndia() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS country_phone_region_prefix_mapping")
                .contains("UNIQUE KEY uq_country_phone_region_prefix")
                .contains("creator_phone_region_code")
                .contains("creator_phone_region_name")
                .contains("information_schema.columns")
                .contains("SUBSTRING(preview.owner_phone, 3, 4)")
                .contains("hstsethi/in-mob-prefix")
                .contains("153ba809d514e74f62a1dc88fb10f0cb1a562e0e")
                .contains("非当前所在地");
        assertThat(sql.split("\\('IN', '").length - 1).isGreaterThanOrEqualTo(1_700);
    }

    @Test
    void runtimeMappersNoLongerReadOrWritePhoneRegionFields() throws Exception {
        String previewXml = Files.readString(PREVIEW_MAPPER, StandardCharsets.UTF_8);
        String listXml = Files.readString(LIST_MAPPER, StandardCharsets.UTF_8);

        assertThat(previewXml)
                .doesNotContain("creatorPhoneRegion")
                .doesNotContain("creator_phone_region");
        assertThat(listXml)
                .doesNotContain("creatorPhoneRegion")
                .doesNotContain("creator_phone_region");
    }
}
