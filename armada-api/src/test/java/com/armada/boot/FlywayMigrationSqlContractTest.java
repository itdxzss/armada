package com.armada.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** MySQL Flyway 脚本独占行注释契约，阻止部署阶段才暴露的双横线注释错误。 */
class FlywayMigrationSqlContractTest {

    private static final Path MIGRATION_DIRECTORY =
            Path.of("src/main/resources/db/migration");
    private static final Pattern INVALID_MYSQL_STANDALONE_DASH_COMMENT =
            Pattern.compile("^\\s*--\\S.*$");

    @Test
    void mysqlStandaloneDashCommentsContainRequiredWhitespace() throws IOException {
        List<String> invalidComments = new ArrayList<>();

        try (Stream<Path> files = Files.list(MIGRATION_DIRECTORY)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    if (INVALID_MYSQL_STANDALONE_DASH_COMMENT.matcher(lines.get(index)).matches()) {
                        invalidComments.add(file.getFileName() + ":" + (index + 1) + " " + lines.get(index));
                    }
                }
            }
        }

        assertThat(invalidComments)
                .as("MySQL 的独占行双横线注释必须在 -- 后包含空白字符")
                .isEmpty();
    }
}
