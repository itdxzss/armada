package com.armada.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Flyway 版本契约测试，避免重复版本导致应用在 Bean 初始化前启动失败。 */
class FlywayMigrationVersionContractTest {

    private static final Path MIGRATION_DIRECTORY =
            Path.of("src/main/resources/db/migration");
    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V(.+?)__.+\\.sql$");

    @Test
    void versionedMigrationsUseUniqueNormalizedVersions() throws IOException {
        Map<String, List<String>> scriptsByVersion = new LinkedHashMap<>();

        try (Stream<Path> files = Files.list(MIGRATION_DIRECTORY)) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(fileName -> collectVersion(scriptsByVersion, fileName));
        }

        List<String> duplicates = scriptsByVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "V" + entry.getKey() + ": " + String.join(", ", entry.getValue()))
                .toList();

        assertThat(duplicates)
                .as("每个 Flyway 版本只能有一个迁移脚本，否则应用无法启动")
                .isEmpty();
    }

    @Test
    void normalizesFlywayEquivalentVersionSpellings() {
        assertThat(normalizeVersion("001")).isEqualTo("1");
        assertThat(normalizeVersion("1.0")).isEqualTo("1");
        assertThat(normalizeVersion("1_0_0")).isEqualTo("1");
        assertThat(normalizeVersion("1.0.1")).isEqualTo("1.0.1");
    }

    private static void collectVersion(Map<String, List<String>> scriptsByVersion, String fileName) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(fileName);
        if (!matcher.matches()) {
            return;
        }
        String normalizedVersion = normalizeVersion(matcher.group(1));
        scriptsByVersion.computeIfAbsent(normalizedVersion, ignored -> new ArrayList<>()).add(fileName);
    }

    /** 与 Flyway 一致：忽略数字前导零、版本分隔符差异和末尾零版本段。 */
    private static String normalizeVersion(String rawVersion) {
        List<String> parts = Stream.of(rawVersion.split("[._]"))
                .map(BigInteger::new)
                .map(BigInteger::toString)
                .collect(Collectors.toCollection(ArrayList::new));
        while (parts.size() > 1 && "0".equals(parts.get(parts.size() - 1))) {
            parts.remove(parts.size() - 1);
        }
        return String.join(".", parts);
    }
}
