package com.armada.promotion.pairing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** 公共配对回调和跨租户过期扫描的 SQL 形状合同。 */
class PromotionPairingSessionMapperSqlContractTest {

    private static final String RESOURCE = "mapper/promotion/pairing/PromotionPairingSessionMapper.xml";

    @Test
    void kafkaLookupRequiresTheOneTimeProtocolAccountAndActiveStatus() throws IOException {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"selectActiveByProtocolAccountId\"");
        String query = xml.substring(start, xml.indexOf("</select>", start));

        assertThat(query).contains("active_protocol_account_id = #{protocolAccountId}");
        assertThat(query).doesNotContain("ORDER BY updated_at", "phone =");
    }

    @Test
    void expiryScanIsBoundedAndUsesLifecycleIndexOrder() throws Exception {
        String xml = mapperXml();
        int start = xml.indexOf("<select id=\"selectExpiredActive\"");
        String query = xml.substring(start, xml.indexOf("</select>", start));

        assertThat(query).contains("status IN (1, 2, 3)");
        assertThat(query).contains("expires_at &lt;= #{now}");
        assertThat(query).contains("ORDER BY expires_at, id");
        assertThat(query).contains("LIMIT #{limit}");

        InterceptorIgnore annotation = PromotionPairingSessionMapper.class
                .getMethod("selectExpiredActive", long.class, int.class)
                .getAnnotation(InterceptorIgnore.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.tenantLine()).isEqualTo("true");
    }

    private String mapperXml() throws IOException {
        try (var stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
