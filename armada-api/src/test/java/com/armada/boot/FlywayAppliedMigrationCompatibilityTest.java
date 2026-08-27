package com.armada.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.StringResource;
import org.junit.jupiter.api.Test;

/** 已部署测试环境的 Flyway 迁移兼容性测试，禁止遗漏或改写已执行脚本。 */
class FlywayAppliedMigrationCompatibilityTest {

    private static final Path MIGRATION_DIRECTORY =
            Path.of("src/main/resources/db/migration");

    @Test
    void test1AppliedMigrationsKeepOriginalChecksums() throws IOException {
        assertAppliedMigration(
                "V082__marketing_export_country_and_joined_at.sql",
                -1_524_599_230);
        assertAppliedMigration(
                "V083__marketing_task_export_job.sql",
                1_140_684_827);
        assertAppliedMigration(
                "V083_1__account_group_membership_last_exit.sql",
                -1_724_885_338);
        assertAppliedMigration(
                "V085__account_group_membership_last_exit.sql",
                810_248_183);
        assertAppliedMigration(
                "V090__group_folder.sql",
                -1_682_709_825);
        assertAppliedMigration(
                "V091__whatsapp_group_departed_member.sql",
                1_816_449_594);
        assertAppliedMigration(
                "V092__whatsapp_group_member_join_fact.sql",
                -1_133_243_864);
        assertAppliedMigration(
                "V093__pull_task_normal_link_execution.sql",
                -1_160_226_712);
        assertAppliedMigration(
                "V094__pull_task_group_account_membership_result.sql",
                -1_117_482_777);
        assertAppliedMigration(
                "V095__pull_task_standard_full_form_settings.sql",
                -1_758_254_373);
        assertAppliedMigration(
                "V096__whatsapp_group_member_cache.sql",
                380_433_951);
        assertAppliedMigration(
                "V097__group_departure_unknown_metadata.sql",
                -1_292_654_150);
        assertAppliedMigration(
                "V098__group_list_history_metadata.sql",
                295_574_265);
        assertAppliedMigration(
                "V099__group_folder_invite_auto_backfill.sql",
                1_758_125_011);
        assertAppliedMigration(
                "V101__normal_group_creation.sql",
                419_410_967);
        assertAppliedMigration(
                "V141__account_user_data_ownership.sql",
                -1_459_798_918);
        assertAppliedMigration(
                "V142__marketing_template_user_data_ownership.sql",
                2_000_065_654);
        assertAppliedMigration(
                "V143__marketing_task_user_data_ownership.sql",
                2_102_297_802);
        assertAppliedMigration(
                "V144__group_creation_marketing_task_user_data_ownership.sql",
                -495_904_787);
        assertAppliedMigration(
                "V145__join_task_user_data_ownership.sql",
                -1_206_226_512);
        assertAppliedMigration(
                "V146__pull_task_user_data_ownership.sql",
                233_407_823);
        assertAppliedMigration(
                "V147__pull_task_group_avatar_user_data_ownership.sql",
                1_345_200_728);
        assertAppliedMigration(
                "V148__group_user_data_ownership.sql",
                -931_534_151);
        assertAppliedMigration(
                "V149__normal_group_creation_user_data_ownership.sql",
                548_113_055);
        assertAppliedMigration(
                "V150__historical_group_pull_user_data_ownership.sql",
                1_785_959_301);
        assertAppliedMigration(
                "V151__promotion_capi_outbox_user_data_ownership.sql",
                -1_137_908_149);
        assertAppliedMigration(
                "V152__marketing_export_job_data_scope.sql",
                -67_117_491);
    }

    private static void assertAppliedMigration(String fileName, int expectedChecksum)
            throws IOException {
        Path migration = MIGRATION_DIRECTORY.resolve(fileName);
        assertThat(migration)
                .as("test1 已执行的迁移必须继续随应用发布")
                .exists();
        String sql = Files.readString(migration, StandardCharsets.UTF_8);

        assertThat(ChecksumCalculator.calculate(new StringResource(sql)))
                .as("test1 已执行迁移的 Flyway checksum 不得变化: %s", fileName)
                .isEqualTo(expectedChecksum);
    }
}
