package com.armada.promotion.channel.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class PromotionChannelMapperSqlContractTest {

    private static final String RESOURCE = "mapper/promotion/channel/PromotionChannelMapper.xml";

    @Test
    void pageSqlPushesAllFiltersPaginationAndUpperUserOwnerIdsToMysql() throws IOException {
        String xml = mapperXml();
        int filterStart = xml.indexOf("<sql id=\"pageFilter\">");
        String pageFilter = xml.substring(filterStart, xml.indexOf("</sql>", filterStart));

        assertThat(xml).contains("<sql id=\"pageFilter\">");
        assertThat(xml).contains("c.target_country_value = #{targetCountry}");
        assertThat(xml).doesNotContain("mixedTargetCountry", "c.target_country_id IS NULL");
        assertThat(xml).contains("d.landing_template_id = #{landingTemplateId}");
        assertThat(xml).contains("c.owner_user_id = #{creatorUserId}");
        assertThat(xml).contains("collection=\"ownerUserIds\"");
        assertThat(xml).contains("LIMIT #{offset}, #{pageSize}");
        assertThat(pageFilter).doesNotContain("#{tenantId}", "#{tenant_id}");
    }

    @Test
    void insertAndPageProjectionUseCountryOptionValuesInsteadOfDatabaseIds() throws IOException {
        String xml = mapperXml();

        assertThat(xml).contains("target_country_value, preselected_country_value");
        assertThat(xml).contains("#{targetCountry}, #{preselectedCountry}");
        assertThat(xml).contains("c.target_country_value AS targetCountry");
        assertThat(xml).contains("c.preselected_country_value AS preselectedCountry");
        assertThat(xml).doesNotContain("#{targetCountryId}", "#{preselectedCountryId}");
        assertThat(xml).contains("theme_color, is_app_download_shown");
        assertThat(xml).contains("#{themeColor}, #{isAppDownloadShown}");
    }

    @Test
    void runtimeLookupIsMinimalAndValidatesCodeHostStatusAndTenantConsistency() throws IOException {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"selectRuntimeByCodeAndHost\"");
        assertThat(start).isGreaterThanOrEqualTo(0);
        String runtime = xml.substring(start, xml.indexOf("</select>", start));

        assertThat(runtime).contains("c.channel_code = #{channelCode}");
        assertThat(runtime).contains("d.domain_host = #{domainHost}");
        assertThat(runtime).contains("c.status = 1", "c.deleted_at IS NULL");
        assertThat(runtime).contains("INNER JOIN tenant tenant_registry");
        assertThat(runtime).contains("tenant_registry.id = c.tenant_id");
        assertThat(runtime).contains("tenant_registry.status = 1");
        assertThat(runtime).contains("d.tenant_id = c.tenant_id", "t.tenant_id = c.tenant_id");
        assertThat(runtime).contains("t.template_code AS templateCode");
        assertThat(runtime).contains("c.theme_color AS themeColor");
        assertThat(runtime).contains("c.is_app_download_shown AS isAppDownloadShown");
        assertThat(runtime).doesNotContain("tracking_id", "access_token", "owner_user_id");
    }

    @Test
    void domainLookupSupportsBothSidesOfTemplateDomainUniqueness() throws IOException {
        String xml = mapperXml();

        assertThat(xml).contains("<select id=\"selectActiveDomainByHost\"");
        assertThat(xml).contains("WHERE domain_host = #{domainHost}");
        assertThat(xml).contains("<select id=\"selectActiveDomainByTemplateId\"");
        assertThat(xml).contains("WHERE landing_template_id = #{templateId}");
        assertThat(xml).contains("AND deleted_at IS NULL");
        assertThat(xml).contains("<select id=\"selectActiveDomainByHostForUpdate\"");
        assertThat(xml).contains("<select id=\"selectActiveDomainByTemplateIdForUpdate\"");
        assertThat(xml).contains("<select id=\"selectActiveDomainByIdForUpdate\"");
        assertThat(xml).contains("AND is_active = 1");
        assertThat(xml).contains("FOR UPDATE");
    }

    @Test
    void listNeverSelectsTokenCiphertextOrFingerprint() throws IOException {
        String xml = mapperXml();
        String selectPage = xml.substring(xml.indexOf("<select id=\"selectPage\""),
                xml.indexOf("</select>", xml.indexOf("<select id=\"selectPage\"")));

        assertThat(selectPage)
                .doesNotContain("access_token_ciphertext", "token_fingerprint", "encryption_key_id");
    }

    @Test
    void detailSelectsEditableFieldsAndOnlyProjectsTokenConfiguredFlag() throws IOException {
        String xml = mapperXml();
        int detailStart = xml.indexOf("<select id=\"selectDetailById\"");
        assertThat(detailStart).isGreaterThanOrEqualTo(0);
        String detail = xml.substring(detailStart, xml.indexOf("</select>", detailStart));

        assertThat(detail).contains("d.domain_host AS domain");
        assertThat(detail).contains("tc.tracking_id AS trackingId");
        assertThat(detail).contains("tc.lead_event_name AS leadEventName");
        assertThat(detail).contains("access_token_ciphertext IS NOT NULL");
        assertThat(detail).contains("AS accessTokenConfigured");
        assertThat(detail).contains("INNER JOIN promotion_domain d");
        assertThat(detail).contains("d.deleted_at IS NULL");
        assertThat(detail).contains("LEFT JOIN promotion_channel_tracking_config tc");
        assertThat(detail).contains("tc.deleted_at IS NULL");
        assertThat(detail).contains("WHERE c.id = #{id}");
        assertThat(detail).contains("AND c.deleted_at IS NULL");
        assertThat(detail).contains("LIMIT 1");
        assertThat(detail).containsOnlyOnce("tc.access_token_ciphertext");
        assertThat(detail).containsOnlyOnce("tc.token_fingerprint");
        assertThat(detail).containsOnlyOnce("tc.encryption_key_id");
        assertThat(detail).doesNotContain(
                "AS accessTokenCiphertext", "AS tokenFingerprint", "AS encryptionKeyId");
    }

    @Test
    void updateSqlPreservesTokenUnlessNewCiphertextIsProvided() throws IOException {
        String xml = mapperXml();
        int lockSelectStart = xml.indexOf("<select id=\"selectActiveChannelById\"");
        String lockSelect = xml.substring(lockSelectStart, xml.indexOf("</select>", lockSelectStart));

        assertThat(xml).contains("<select id=\"countReusableTrackingToken\" resultType=\"int\">");
        assertThat(xml).contains("provider_type = #{providerType}");
        assertThat(xml).contains("tracking_id = #{trackingId}");
        assertThat(xml).contains("access_token_ciphertext IS NOT NULL");
        assertThat(lockSelect).contains("FOR UPDATE").doesNotContain("LIMIT");
        assertThat(xml).contains("<update id=\"updateChannel\">");
        assertThat(xml).contains("<update id=\"updateTrackingConfig\">");
        assertThat(xml).contains("<if test=\"accessTokenCiphertext != null\">");
        assertThat(xml).contains("access_token_ciphertext = #{accessTokenCiphertext}");
        assertThat(xml).contains("deleted_at = NULL");
    }

    @Test
    void probeSqlSelectsSensitiveConfigurationOnlyForProbeAndUsesAtomicStateUpdates() throws IOException {
        String xml = mapperXml();
        int probeStart = xml.indexOf("<select id=\"selectProbeConfigByChannelId\"");
        assertThat(probeStart).isGreaterThanOrEqualTo(0);
        String probe = xml.substring(probeStart, xml.indexOf("</select>", probeStart));

        assertThat(probe).contains("c.id AS channelId");
        assertThat(probe).contains("tc.access_token_ciphertext AS accessTokenCiphertext");
        assertThat(probe).contains("d.deleted_at IS NULL", "c.deleted_at IS NULL", "tc.deleted_at IS NULL");
        assertThat(probe).doesNotContain("SELECT *");
        assertThat(xml).contains("<update id=\"markProbeRunning\">");
        assertThat(xml).contains("last_probe_status = 0");
        assertThat(xml).contains("last_probed_at &lt;= #{staleBefore}");
        assertThat(xml).contains("last_probed_at &lt;= #{cooldownBefore}");
        assertThat(xml).contains("token_fingerprint = #{row.tokenFingerprint}");
        assertThat(xml).contains("<update id=\"updateProbeResult\">");
        assertThat(xml).contains("last_probe_status = #{row.lastProbeStatus}");
        assertThat(xml).contains("last_probe_error_message = #{row.lastProbeErrorMessage}");
        assertThat(xml).contains("token_fingerprint = #{row.tokenFingerprint}");
        assertThat(xml).contains("AND last_probe_status = 0");
        assertThat(xml).contains("AND last_probed_at = #{startedAt}");
    }

    @Test
    void providerChangeCanClearOldTrackingCredentialsWithoutDeletingConfiguration() throws IOException {
        String xml = mapperXml();

        assertThat(xml).contains("<update id=\"clearTrackingCredentials\">");
        assertThat(xml).contains("access_token_ciphertext = NULL");
        assertThat(xml).contains("encryption_key_id = NULL");
        assertThat(xml).contains("token_fingerprint = NULL");
        assertThat(xml).contains("token_expires_at = NULL");
    }

    @Test
    void deleteSqlUsesSoftDeleteAndNeverPhysicalDelete() throws IOException {
        String xml = mapperXml();

        assertThat(xml).contains("<select id=\"selectActiveDomainByChannelIdForUpdate\"");
        assertThat(xml).contains("WHERE c.id = #{channelId}");
        assertThat(xml).contains("<update id=\"softDeleteTrackingConfig\">");
        assertThat(xml).contains("<update id=\"softDeleteChannel\">");
        assertThat(xml).contains("<select id=\"selectAnyActiveChannelIdByDomainForUpdate\"");
        assertThat(xml).contains("WHERE promotion_domain_id = #{domainId}");
        assertThat(xml).contains("<update id=\"softDeleteDomain\">");
        assertThat(xml).contains("SET deleted_at = #{deletedAt}");
        assertThat(xml).contains("WHERE id = #{id}", "WHERE channel_id = #{channelId}");
        assertThat(xml).doesNotContain("<delete", "DELETE FROM promotion_channel");
    }

    @Test
    void deleteReferenceLockCarriesTenantIdWithoutTenantInterceptorRewrite() throws IOException {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"selectAnyActiveChannelIdByDomainForUpdate\"");
        assertThat(start).isGreaterThanOrEqualTo(0);
        String lockSql = xml.substring(start, xml.indexOf("</select>", start));

        assertThat(lockSql).contains("AND tenant_id = #{tenantId}");
        assertThat(lockSql).containsPattern("(?s)LIMIT 1\\s+FOR UPDATE");

        Method mapperMethod = Arrays.stream(PromotionChannelMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("selectAnyActiveChannelIdByDomainForUpdate"))
                .findFirst()
                .orElseThrow();
        InterceptorIgnore interceptorIgnore = mapperMethod.getAnnotation(InterceptorIgnore.class);
        assertThat(interceptorIgnore).isNotNull();
        assertThat(interceptorIgnore.tenantLine()).isEqualTo("true");
        assertThat(mapperMethod.getParameterCount()).isEqualTo(2);
    }

    private String mapperXml() throws IOException {
        try (var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
