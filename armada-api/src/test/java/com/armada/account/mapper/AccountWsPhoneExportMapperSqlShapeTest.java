package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountWsPhoneExportMapperSqlShapeTest {

    private static final String MAPPER_XML = "/mapper/account/AccountMapper.xml";

    @Test
    void exportQueryKeepsTenantSafeNormalAccountShape() throws IOException {
        String xml = new String(
                getClass().getResourceAsStream(MAPPER_XML).readAllBytes(),
                StandardCharsets.UTF_8);

        String selectSql = selectBlock(xml, "selectNormalWsPhonesByIds");
        String projection = selectSql.substring(
                selectSql.indexOf("SELECT") + "SELECT".length(),
                selectSql.indexOf("FROM account a"));

        assertThat(selectSql)
                .contains("resultType=\"com.armada.account.model.vo.AccountWsPhoneExportRow\"")
                .contains("INNER JOIN account_state s")
                .contains("s.account_id = a.id")
                .contains("s.tenant_id = a.tenant_id")
                .contains("a.deleted_at IS NULL")
                .contains("s.account_state = #{normalAccountState}")
                .contains("a.id IN")
                .contains("<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>")
                .contains("ORDER BY a.id ASC");
        assertThat(projection.replaceAll("\\s+", " ").trim())
                .isEqualTo("a.id, a.ws_phone AS wsPhone");
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
