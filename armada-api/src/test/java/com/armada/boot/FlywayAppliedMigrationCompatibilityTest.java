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
                "V085__account_group_membership_last_exit.sql",
                810_248_183);
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
