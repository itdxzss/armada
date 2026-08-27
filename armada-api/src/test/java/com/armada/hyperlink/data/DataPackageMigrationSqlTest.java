package com.armada.hyperlink.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** V153 数据包四表、关键约束与索引的 Flyway 结构合同测试。 */
class DataPackageMigrationSqlTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V153__hyperlink_data_package.sql");

    @Test
    void migrationDefinesOnlyTheFourOwnedTablesWithGenerationAndReadModelConstraints()
            throws Exception {
        String sql = Files.readString(MIGRATION).toLowerCase().replaceAll("\\s+", " ");

        assertThat(sql).contains(
                "create table if not exists data_package (",
                "create table if not exists data_package_phone (",
                "create table if not exists data_package_stat (",
                "create table if not exists data_package_import (");
        assertThat(sql).contains(
                "unique key uq_data_package_name (tenant_id, package_name, is_active)",
                "unique key uq_data_package_phone (tenant_id, data_package_id, generation, phone)",
                "key idx_data_package_phone_pick (tenant_id, data_package_id, generation, pool_status, id)",
                "key idx_data_package_import_generation (tenant_id, data_package_id, generation, status, finished_at)");
        assertThat(sql).contains("deleted_by bigint default null comment '删除人user_id'");
        assertThat(sql).doesNotContain(
                "hyperlink_template",
                "hyperlink_task",
                "hyperlink_strategy",
                "sys_menu");
    }
}
