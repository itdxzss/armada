package com.armada.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import org.junit.jupiter.api.Test;

/** 第一套测试库已执行迁移的文件名与校验和契约。 */
class FlywayMigrationHistoryContractTest {

    private static final Path MIGRATION_DIRECTORY = Path.of("src/main/resources/db/migration");
    private static final Map<String, MigrationSpec> EXPECTED_HISTORY = Map.ofEntries(
            Map.entry("061", new MigrationSpec("V061__promotion_template_channel_statistics.sql", 1676422917)),
            Map.entry("062", new MigrationSpec("V062__promotion_channel_country_values.sql", 232278498)),
            Map.entry("063", new MigrationSpec("V063__promotion_template_visibility_and_seed.sql", 1573867056)),
            Map.entry("064", new MigrationSpec("V064__promotion_template_single_domain.sql", -611435249)),
            Map.entry("065", new MigrationSpec("V065__promotion_domain_soft_delete_uniqueness.sql", 1198524220)),
            Map.entry("066", new MigrationSpec("V066__promotion_channel_runtime_config.sql", 748421947)),
            Map.entry("067", new MigrationSpec("V067__promotion_pairing_account_phone_index.sql", -1457194837)),
            Map.entry("068", new MigrationSpec("V068__promotion_pairing_ip_reservation.sql", 1122157768)),
            Map.entry("069", new MigrationSpec("V069__promotion_pairing_session.sql", -1009231184)),
            Map.entry("070", new MigrationSpec("V070__group_pull_marketing.sql", -168361012)),
            Map.entry("071", new MigrationSpec("V071__system_management_rbac.sql", -315144987)),
            Map.entry("072", new MigrationSpec("V072__default_tenant_admin_user.sql", 166505662)),
            Map.entry("073", new MigrationSpec("V073__default_admin_password.sql", -1156189687)),
            Map.entry("074", new MigrationSpec("V074__default_admin_password_policy.sql", -1863275304)),
            Map.entry("075", new MigrationSpec("V075__restore_task_center_menu_structure.sql", 2104574531)),
            Map.entry("076", new MigrationSpec("V076__remove_obsolete_group_management_menu.sql", 271914839)),
            Map.entry("077", new MigrationSpec("V077__account_desired_login_state.sql", -1202454221)),
            Map.entry("078", new MigrationSpec("V078__pull_task_and_channel_stats.sql", 1996711734)),
            Map.entry("079", new MigrationSpec("V079__optimize_ip_country_stats.sql", -2121604516)));

    @Test
    void migrationsFromV061ThroughV079MatchTheDeployedHistory() throws IOException {
        Set<String> expectedFiles = EXPECTED_HISTORY.values().stream()
                .map(MigrationSpec::fileName)
                .collect(Collectors.toSet());

        Set<String> actualFiles;
        try (Stream<Path> files = Files.list(MIGRATION_DIRECTORY)) {
            actualFiles = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.matches("^V0(6[1-9]|7[0-9])__.+\\.sql$"))
                    .collect(Collectors.toSet());
        }

        assertThat(actualFiles).containsExactlyInAnyOrderElementsOf(expectedFiles);
        EXPECTED_HISTORY.forEach((version, spec) -> assertMigration(version, spec));
    }

    private static void assertMigration(String version, MigrationSpec spec) {
        Path migration = MIGRATION_DIRECTORY.resolve(spec.fileName());
        assertThat(migration).as("V%s 迁移文件", version).exists();
        FileSystemResource resource = new FileSystemResource(
                null, migration.toString(), StandardCharsets.UTF_8, false);
        assertThat(ChecksumCalculator.calculate(resource))
                .as("V%s Flyway checksum", version)
                .isEqualTo(spec.checksum());
    }

    private record MigrationSpec(String fileName, int checksum) {
    }
}
