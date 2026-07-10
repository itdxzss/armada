package com.armada.marketing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 模板与任务并发写入的 SQL 门禁，防止后续重构意外移除行锁或固定锁顺序。 */
class MarketingTemplateMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/MarketingTemplateMapper.xml";

    @Test
    void taskCreationAndTemplateDeletionUseRowLocks() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String createLockSql = selectBlock(xml, "selectByIdForUpdate");
        String deleteLockSql = selectBlock(xml, "selectExistingIdsForUpdate");

        assertThat(createLockSql)
                .contains("WHERE id = #{id} AND deleted_at IS NULL")
                .contains("FOR UPDATE");
        assertThat(deleteLockSql)
                .contains("WHERE deleted_at IS NULL AND id IN")
                .contains("ORDER BY id ASC")
                .contains("FOR UPDATE");
    }

    private static String selectBlock(String xml, String id) {
        String startTag = "<select id=\"" + id + "\"";
        int start = xml.indexOf(startTag);
        assertThat(start).as("mapper select " + id + " exists").isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</select>", start);
        assertThat(end).as("mapper select " + id + " closes").isGreaterThan(start);
        return xml.substring(start, end);
    }
}
