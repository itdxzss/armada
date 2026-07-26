package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountGroupMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.Test;

/** 人工账号迁移条件更新与分组营销占用并发保护 SQL 测试。 */
class AccountGroupMarketingLockGuardTest {

    @Test
    void migrationUsesConditionalUpdateInsteadOfExplicitLockingReads() throws IOException {
        String accountXml = resource("/mapper/account/AccountMapper.xml");
        String groupXml = resource("/mapper/account/AccountGroupMapper.xml");

        assertThat(selectBlock(accountXml, "selectActiveByIds"))
                .doesNotContain("FOR UPDATE");
        assertThat(selectBlock(groupXml, "selectByIds"))
                .contains("ORDER BY id")
                .doesNotContain("FOR UPDATE");
        assertThat(updateBlock(accountXml, "migrateGroupIfAvailable"))
                .contains("UPDATE account a")
                .contains("JOIN account_group source_group")
                .contains("JOIN account_group target_group")
                .contains("a.account_group_id = #{sourceGroupId}")
                .contains("source_group.marketing_occupancy_task_id IS NULL")
                .contains("target_group.marketing_occupancy_task_id IS NULL")
                .contains("FROM group_pull_marketing_task active_task")
                .contains("active_task.builder_group_id = #{sourceGroupId}")
                .contains("active_task.resource_status IN (2, 3)")
                .doesNotContain("FOR UPDATE");
        assertThat(selectBlock(groupXml, "countActiveBuilderGroupReferences"))
                .contains("FROM group_pull_marketing_task")
                .contains("builder_group_id IN")
                .contains("resource_status IN (2, 3)");
    }

    @Test
    void tenantInterceptorParsesConditionalMigrationUpdateWithoutLockClause() throws IOException {
        String accountXml = resource("/mapper/account/AccountMapper.xml");
        String updateSql = renderSourceGroupUpdate(updateBlock(accountXml, "migrateGroupIfAvailable"));
        TenantLineInnerInterceptor interceptor = new TenantLineInnerInterceptor(() -> new LongValue(7L));

        String parsedSql = interceptor.parserSingle(updateSql, null);

        assertThat(parsedSql)
                .contains("a.tenant_id = 7")
                .contains("source_group.tenant_id = a.tenant_id")
                .contains("target_group.tenant_id = a.tenant_id")
                .contains("active_task.tenant_id = a.tenant_id")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    void lockedGroupReadUsesExplicitTenantAndKeepsValidMysqlClauseOrder() throws Exception {
        String groupXml = resource("/mapper/account/AccountGroupMapper.xml");
        String lockSql = selectBlock(groupXml, "selectByTenantAndIdsForUpdate");

        assertThat(lockSql)
                .contains("WHERE tenant_id = #{tenantId}")
                .contains("ORDER BY id")
                .contains("FOR UPDATE");
        assertThat(lockSql.indexOf("ORDER BY id")).isLessThan(lockSql.indexOf("FOR UPDATE"));

        Method method = AccountGroupMapper.class.getMethod(
                "selectByTenantAndIdsForUpdate", Long.class, List.class);
        InterceptorIgnore annotation = method.getAnnotation(InterceptorIgnore.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.tenantLine()).isEqualTo("true");
    }

    private String resource(String path) throws IOException {
        return new String(getClass().getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8);
    }

    private String selectBlock(String xml, String id) {
        return block(xml, "select", id);
    }

    private String updateBlock(String xml, String id) {
        return block(xml, "update", id);
    }

    private String renderSourceGroupUpdate(String updateBlock) {
        int bodyStart = updateBlock.indexOf('>') + 1;
        String sql = updateBlock.substring(bodyStart)
                .replaceAll("(?s)<otherwise>.*?</otherwise>", "")
                .replaceAll("(?s)<foreach[^>]*>.*?</foreach>", "(101)")
                .replaceAll("</?(?:if|choose|when)[^>]*>", "")
                .replace("#{sourceGroupId}", "10")
                .replace("#{accountGroupId}", "20")
                .replace("#{updatedAt}", "1784966400000")
                .replace("</update>", "");
        return sql.trim();
    }

    private String block(String xml, String tag, String id) {
        String open = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(open);
        assertThat(start).as("%s %s exists", tag, id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</" + tag + ">", start);
        assertThat(end).as("%s %s closes", tag, id).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
