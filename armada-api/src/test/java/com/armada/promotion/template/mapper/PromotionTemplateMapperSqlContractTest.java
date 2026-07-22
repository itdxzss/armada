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
        String updateRemark = fragment(xml, "<update id=\"updateRemark\">", "</update>");
        assertThat(updateRemark).contains("SET remark = #{remark}");
        assertThat(updateRemark).contains("updated_at = #{updatedAt}");
        assertThat(updateRemark).contains("WHERE id = #{id}");
        assertThat(updateRemark).contains("AND status = 1");
        assertThat(updateRemark).contains("AND deleted_at IS NULL");
        assertThat(xml).doesNotContain("#{tenantId}", "#{tenant_id}");
    }

    private static String fragment(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex + end.length());
    }

    private String mapperXml() throws IOException {
        try (var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
