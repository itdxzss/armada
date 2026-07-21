package com.armada.promotion.template.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class PromotionTemplateMapperSqlContractTest {

    private static final String RESOURCE = "mapper/promotion/template/PromotionTemplateMapper.xml";

    @Test
    void pageSqlUsesSharedActiveFilterStableOrderAndMysqlPagination() throws IOException {
        String xml = mapperXml();

        assertThat(xml).contains("<sql id=\"pageFilter\">");
        assertThat(xml).contains("deleted_at IS NULL");
        assertThat(xml).contains("status = 1");
        assertThat(xml).contains("ORDER BY id DESC");
        assertThat(xml).contains("LIMIT #{offset}, #{pageSize}");
        assertThat(xml).contains("CAST(supported_params AS CHAR) AS supportedParamsJson");
        assertThat(xml).doesNotContain("#{tenantId}", "#{tenant_id}");
    }

    private String mapperXml() throws IOException {
        try (var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
