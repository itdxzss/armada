package com.armada.contact.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** V160 发送引擎补列迁移的 SQL 文本契约测试。本机无库，只校验迁移脚本本身。 */
class ContactTaskEngineMigrationSqlTest {

    private static String sql() throws IOException {
        return Files.readString(
                Path.of("src/main/resources/db/migration/V165__contact_task_engine.sql"),
                StandardCharsets.UTF_8);
    }

    @Test
    void addsCurrentRoundNoToTask() throws IOException {
        String text = sql();

        assertThat(text).contains("contact_friend_task");
        assertThat(text).contains("current_round_no");
    }

    @Test
    void addsRoundNoAndCommandIdToRecipient() throws IOException {
        String text = sql();

        assertThat(text).contains("contact_friend_task_recipient");
        assertThat(text).contains("round_no");
        assertThat(text).contains("command_id");
    }

    @Test
    void everyAddedColumnCarriesComment() throws IOException {
        // AGENTS.md 硬要求：新列必须带 COMMENT
        String text = sql();
        long addColumnLines = text.lines().filter(line -> line.contains("ADD COLUMN")).count();
        long commentedLines = text.lines()
                .filter(line -> line.contains("ADD COLUMN") && line.contains("COMMENT"))
                .count();

        assertThat(addColumnLines).isGreaterThanOrEqualTo(3);
        assertThat(commentedLines).isEqualTo(addColumnLines);
    }

    @Test
    void isIdempotentAgainstRepeatedExecution() throws IOException {
        // 与 V157 同一写法：information_schema 探测后再 ALTER，重复执行不炸
        assertThat(sql()).contains("information_schema.columns");
    }
}
