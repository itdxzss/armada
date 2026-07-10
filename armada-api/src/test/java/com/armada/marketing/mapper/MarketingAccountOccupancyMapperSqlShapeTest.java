package com.armada.marketing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.Test;

/**
 * 营销账号当前占用表及 Mapper 的并发约束形状测试。
 *
 * <p>真库测试负责验证唯一键竞争结果；本类先锁定迁移和 SQL 必须具备的数据库闸门。</p>
 */
class MarketingAccountOccupancyMapperSqlShapeTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V049__marketing_account_occupancy.sql");
    private static final Path MAPPER = Path.of(
            "src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml");

    @Test
    void migrationDefinesOneCurrentOwnerPerTenantAccount() throws IOException {
        assertThat(MIGRATION).as("occupancy migration exists").exists();
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS marketing_account_occupancy")
                .contains("tenant_id")
                .contains("account_id")
                .contains("marketing_task_id")
                .contains("occupied_at")
                .contains("UNIQUE KEY uq_marketing_account_occupancy_account (tenant_id, account_id)")
                .contains("KEY idx_marketing_account_occupancy_task (tenant_id, marketing_task_id)")
                .doesNotContain("task_name")
                .doesNotContain("task_end_at");
    }

    @Test
    void mapperUsesDatabaseUniqueGateAndTaskScopedRelease() throws IOException {
        assertThat(MAPPER).as("occupancy mapper exists").exists();
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        String acquireSql = block(xml, "insert", "insertAvailableTaskAccounts");
        String accountOwnersSql = block(xml, "select", "selectOwnersByAccountIds");
        String releaseSql = block(xml, "delete", "releaseByTaskId");
        String releaseByTemplateSql = block(xml, "delete", "releaseByTemplateIds");
        String staleSql = block(xml, "delete", "deleteStale");

        assertThat(acquireSql)
                .contains("INSERT IGNORE INTO marketing_account_occupancy")
                .contains("SELECT DISTINCT")
                .contains("updated_at, tenant_id")
                .contains("mt.tenant_id")
                .contains("mt.status IN (1, 2, 5)")
                .contains("t.marketing_task_id = #{taskId}");
        assertThat(accountOwnersSql)
                .contains("o.account_id IN")
                .contains("collection=\"accountIds\"")
                .contains("mt.status IN (1, 2, 5)")
                .contains("mt.task_name AS taskName");
        assertThat(releaseSql).contains("marketing_task_id = #{taskId}");
        assertThat(releaseByTemplateSql)
                .contains("marketing_task_id IN")
                .contains("SELECT mt.id")
                .contains("mt.marketing_template_id IN");
        assertThat(staleSql)
                .contains("marketing_task_id NOT IN")
                .contains("mt.status IN (1, 2, 5)")
                .doesNotContain("task_end_at");
    }

    @Test
    void tenantInterceptorAddsTenantColumnToAcquireInsertSelect() throws IOException {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);
        String acquireSql = sqlBody(block(xml, "insert", "insertAvailableTaskAccounts"))
                .replace("#{occupiedAt}", "1000")
                .replace("#{taskId}", "91");
        TenantLineInnerInterceptor interceptor = new TenantLineInnerInterceptor(() -> new LongValue(7L));

        String parsedSql = interceptor.parserSingle(acquireSql, null);

        assertThat(parsedSql)
                .contains("updated_at, tenant_id)")
                .contains("1000, 1000, 1000, mt.tenant_id")
                .doesNotContain("1000, 1000, 1000, tenant_id");
    }

    private static String block(String xml, String tag, String id) {
        String startTag = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).as("mapper %s %s exists", tag, id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</" + tag + ">", start);
        assertThat(end).as("mapper %s %s closes", tag, id).isGreaterThan(start);
        return xml.substring(start, end);
    }

    private static String sqlBody(String mapperBlock) {
        int start = mapperBlock.indexOf('>');
        assertThat(start).isGreaterThanOrEqualTo(0);
        return mapperBlock.substring(start + 1)
                .replaceAll("(?s)<!--.*?-->", "")
                .trim();
    }
}
