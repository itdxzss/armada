package com.armada.contact.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 通讯录营销任务 Flyway 脚本契约测试。 */
class ContactTaskMigrationSqlTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V163__contact_friend_task.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void createsThreeTablesEachCarryingTenantColumn() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS contact_friend_task")
                .contains("CREATE TABLE IF NOT EXISTS contact_friend_task_account")
                .contains("CREATE TABLE IF NOT EXISTS contact_friend_task_recipient");
        // 三张表都必须有 tenant_id，否则 MyBatis-Plus 租户拦截器会注入非法条件
        assertThat(sql.split("tenant_id BIGINT NOT NULL")).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void taskCarriesBothStatusFieldsWithCompetitorSemantics() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("is_enabled TINYINT NOT NULL DEFAULT 0")
                .contains("run_status TINYINT NOT NULL DEFAULT 0");
        assertThat(sql).contains("0未开始 1进行中 2已完成 3已暂停 4已停止");
    }

    @Test
    void intervalColumnsKeepOneDecimalSecond() throws IOException {
        // 竞品的发送间隔是带一位小数的秒（最快 0.1s），不能落成整数
        assertThat(sql())
                .contains("msg_interval_min_sec DECIMAL(4,1)")
                .contains("msg_interval_max_sec DECIMAL(4,1)");
    }

    @Test
    void accountTableIsTheReadModelForPerAccountData() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("UNIQUE KEY uq_contact_task_account (task_id, account_id)")
                // 账号数据接口支持按这三列服务端排序
                .contains("KEY idx_contact_task_account_need (task_id, need_send_num)")
                .contains("KEY idx_contact_task_account_sent (task_id, sent_num)")
                .contains("KEY idx_contact_task_account_fail (task_id, fail_num)");
    }

    @Test
    void recipientHasIdempotencyKeyAndSnapshotColumns() throws IOException {
        String sql = sql();

        assertThat(sql)
                .contains("UNIQUE KEY uq_contact_task_recipient (task_id, task_account_id, contact_phone)")
                .contains("KEY idx_contact_task_recipient_pick (task_id, send_status, id)");
        // recipient 存快照，不外键 account_contact：通讯录会变，任务事实不能跟着漂
        assertThat(sql).doesNotContain("account_contact_id");
    }

    @Test
    void everyColumnDefinitionCarriesComment() throws IOException {
        List<String> uncommented = sql().lines()
                .map(String::trim)
                .filter(line -> line.matches("^[a-z_]+ (BIGINT|INT|VARCHAR|CHAR|TINYINT|DECIMAL|JSON).*"))
                .filter(line -> !line.contains("COMMENT"))
                .toList();

        assertThat(uncommented).as("这些列缺 COMMENT").isEmpty();
    }
}
