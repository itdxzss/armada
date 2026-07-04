package com.armada.marketing.mapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingTaskMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/marketing/MarketingTaskMapper.xml";

    @Test
    void marketingAccountSelectionUsesSimpleBusinessPredicate() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String candidateSql = selectBlock(xml, "selectTargetCandidate");
        String treeSql = selectBlock(xml, "selectAccountTreeRows");

        assertThat(candidateSql).contains("p.group_jid AS groupJid");
        assertThat(treeSql).contains("p.group_jid AS group_jid");
        assertThat(candidateSql + treeSql)
                .doesNotContain("account_group_membership")
                .doesNotContain("NOT EXISTS")
                .doesNotContain("m.group_jid")
                .doesNotContain("COALESCE(NULLIF(TRIM(m.group_jid)");
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
