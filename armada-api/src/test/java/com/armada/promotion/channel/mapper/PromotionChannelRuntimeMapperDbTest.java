package com.armada.promotion.channel.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.promotion.channel.model.vo.PromotionChannelRuntimeRow;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DbTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 公开渠道运行时配置的真实 MySQL、跨租户和停用租户安全边界测试。 */
class PromotionChannelRuntimeMapperDbTest extends DbTestBase {

    @Autowired
    private PromotionChannelMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runtimeLookupRequiresCodeHostEnabledTenantAndConsistentRelations() {
        long now = System.currentTimeMillis();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String channelCode = "rt" + suffix.substring(0, 6);
        String tenantOneHost = suffix + "-one.example.test";
        String tenantTwoHost = suffix + "-two.example.test";
        jdbc.update("UPDATE tenant SET status = 1 WHERE id IN (1, 2)");

        long tenantOneTemplateId = insertTemplate(1L, "rt_one_" + suffix, now);
        long tenantTwoTemplateId = insertTemplate(2L, "rt_two_" + suffix, now);
        long tenantOneDomainId = insertDomain(1L, tenantOneHost, tenantOneTemplateId, now);
        long tenantTwoDomainId = insertDomain(2L, tenantTwoHost, tenantTwoTemplateId, now);
        insertChannel(1L, channelCode, tenantOneDomainId, "#112233", 0, now);
        insertChannel(2L, channelCode, tenantTwoDomainId, "#445566", 1, now);

        // 公共请求没有租户上下文；Mapper 必须只依靠 code + host 安全定位所属租户。
        TenantContext.clear();
        PromotionChannelRuntimeRow tenantOne = mapper.selectRuntimeByCodeAndHost(channelCode, tenantOneHost);
        assertThat(tenantOne).isNotNull();
        assertThat(tenantOne.getTemplateCode()).isEqualTo("rt_one_" + suffix);
        assertThat(tenantOne.getThemeColor()).isEqualTo("#112233");
        assertThat(tenantOne.getIsAppDownloadShown()).isZero();
        assertThat(mapper.selectRuntimeByCodeAndHost(channelCode, "wrong.example.test")).isNull();

        PromotionChannelRuntimeRow tenantTwo = mapper.selectRuntimeByCodeAndHost(channelCode, tenantTwoHost);
        assertThat(tenantTwo).isNotNull();
        assertThat(tenantTwo.getTemplateCode()).isEqualTo("rt_two_" + suffix);

        // 租户一旦停用，公共入口必须同步冻结，不能因 @InterceptorIgnore 绕开租户状态。
        jdbc.update("UPDATE tenant SET status = 0 WHERE id = 2");
        assertThat(mapper.selectRuntimeByCodeAndHost(channelCode, tenantTwoHost)).isNull();

        // 渠道停用后也必须立即不可访问。
        jdbc.update(
                "UPDATE promotion_channel SET status = 0 WHERE tenant_id = 1 AND channel_code = ?",
                channelCode);
        assertThat(mapper.selectRuntimeByCodeAndHost(channelCode, tenantOneHost)).isNull();

        // 即使存在无外键约束的脏关系，模板租户与渠道租户不一致也必须 fail-closed。
        String mismatchedHost = suffix + "-mismatch.example.test";
        long mismatchedDomainId = insertDomain(1L, mismatchedHost, tenantTwoTemplateId, now);
        String mismatchedCode = "mm" + suffix.substring(0, 6);
        insertChannel(1L, mismatchedCode, mismatchedDomainId, "#778899", 1, now);
        assertThat(mapper.selectRuntimeByCodeAndHost(mismatchedCode, mismatchedHost)).isNull();
    }

    @Test
    void v066CreatesRuntimeColumnsWithSafeDefaults() {
        assertThat(columnDefinition("theme_color"))
                .isEqualTo(new ColumnDefinition("varchar", 7L, "#e11d48", "NO"));
        assertThat(columnDefinition("is_app_download_shown"))
                .isEqualTo(new ColumnDefinition("tinyint", null, "1", "NO"));
    }

    @Test
    void deleteReferenceLockUsesValidMysqlSyntaxAndExplicitTenantBoundary() {
        long now = System.currentTimeMillis();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String channelCode = "dl" + suffix.substring(0, 6);
        long templateId = insertTemplate(TEST_TENANT_ID, "delete_lock_" + suffix, now);
        long domainId = insertDomain(TEST_TENANT_ID, suffix + "-delete.example.test", templateId, now);
        insertChannel(TEST_TENANT_ID, channelCode, domainId, "#112233", 1, now);
        Long channelId = jdbc.queryForObject(
                "SELECT id FROM promotion_channel WHERE tenant_id = ? AND channel_code = ?",
                Long.class,
                TEST_TENANT_ID,
                channelCode);

        // 故意切到其他租户上下文：该锁查询只能由显式 tenantId 决定边界，不能再被租户插件改写 SQL。
        TenantContext.set(2L);
        assertThat(mapper.selectAnyActiveChannelIdByDomainForUpdate(TEST_TENANT_ID, domainId))
                .isEqualTo(channelId);
        assertThat(mapper.selectAnyActiveChannelIdByDomainForUpdate(2L, domainId)).isNull();
    }

    private long insertTemplate(long tenantId, String templateCode, long now) {
        jdbc.update(
                "INSERT INTO promotion_landing_template "
                        + "(tenant_id, template_code, template_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 1, ?, ?)",
                tenantId, templateCode, "运行时测试模板", now, now);
        return jdbc.queryForObject(
                "SELECT id FROM promotion_landing_template WHERE tenant_id = ? AND template_code = ?",
                Long.class,
                tenantId,
                templateCode);
    }

    private long insertDomain(long tenantId, String host, long templateId, long now) {
        jdbc.update(
                "INSERT INTO promotion_domain "
                        + "(tenant_id, domain_host, landing_template_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                tenantId, host, templateId, now, now);
        return jdbc.queryForObject(
                "SELECT id FROM promotion_domain WHERE domain_host = ? AND deleted_at IS NULL",
                Long.class,
                host);
    }

    private void insertChannel(
            long tenantId,
            String channelCode,
            long domainId,
            String themeColor,
            int showAppDownload,
            long now) {
        jdbc.update(
                "INSERT INTO promotion_channel "
                        + "(tenant_id, channel_code, channel_name, owner_user_id, promotion_domain_id, "
                        + "target_country_value, preselected_country_value, theme_color, "
                        + "is_app_download_shown, platform, is_in_app_open_allowed, "
                        + "is_marketing_allowed, status, created_by, updated_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 20001, ?, 'MIXED', 'IN', ?, ?, 1, 1, 1, 1, 20001, 20001, ?, ?)",
                tenantId, channelCode, "运行时测试渠道", domainId, themeColor, showAppDownload, now, now);
    }

    private ColumnDefinition columnDefinition(String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type, character_maximum_length, column_default, is_nullable "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'promotion_channel' "
                        + "AND column_name = ?",
                (rs, rowNum) -> new ColumnDefinition(
                        rs.getString("data_type"),
                        rs.getObject("character_maximum_length", Long.class),
                        rs.getString("column_default"),
                        rs.getString("is_nullable")),
                columnName);
    }

    private record ColumnDefinition(
            String dataType,
            Long characterLength,
            String defaultValue,
            String nullable) {
    }
}
