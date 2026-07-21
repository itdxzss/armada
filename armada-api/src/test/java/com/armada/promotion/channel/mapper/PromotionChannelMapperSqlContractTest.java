package com.armada.promotion.channel.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class PromotionChannelMapperSqlContractTest {

    private static final String RESOURCE = "mapper/promotion/channel/PromotionChannelMapper.xml";

    @Test
    void pageSqlPushesAllFiltersPaginationAndUpperUserOwnerIdsToMysql() throws IOException {
        String xml = mapperXml();

        assertThat(xml).contains("<sql id=\"pageFilter\">");
        assertThat(xml).contains("c.target_country_value = #{targetCountry}");
        assertThat(xml).doesNotContain("mixedTargetCountry", "c.target_country_id IS NULL");
        assertThat(xml).contains("d.landing_template_id = #{landingTemplateId}");
        assertThat(xml).contains("c.owner_user_id = #{creatorUserId}");
        assertThat(xml).contains("collection=\"ownerUserIds\"");
        assertThat(xml).contains("LIMIT #{offset}, #{pageSize}");
        assertThat(xml).doesNotContain("#{tenantId}", "#{tenant_id}");
    }

    @Test
    void insertAndPageProjectionUseCountryOptionValuesInsteadOfDatabaseIds() throws IOException {
        String xml = mapperXml();

        assertThat(xml).contains("target_country_value, preselected_country_value");
        assertThat(xml).contains("#{targetCountry}, #{preselectedCountry}");
        assertThat(xml).contains("c.target_country_value AS targetCountry");
        assertThat(xml).contains("c.preselected_country_value AS preselectedCountry");
        assertThat(xml).doesNotContain("#{targetCountryId}", "#{preselectedCountryId}");
    }

    @Test
    void listNeverSelectsTokenCiphertextOrFingerprint() throws IOException {
        String xml = mapperXml();
        String selectPage = xml.substring(xml.indexOf("<select id=\"selectPage\""),
                xml.indexOf("</select>", xml.indexOf("<select id=\"selectPage\"")));

        assertThat(selectPage)
                .doesNotContain("access_token_ciphertext", "token_fingerprint", "encryption_key_id");
    }

    private String mapperXml() throws IOException {
        try (var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
