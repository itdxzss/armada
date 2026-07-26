package com.armada.resource.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** IP 国家统计索引使用契约，防止在国家字段上重新引入使索引失效的函数。 */
class IpProxyStatsSqlShapeTest {

    private static final Path MAPPER_XML =
            Path.of("src/main/resources/mapper/resource/IpProxyMapper.xml");
    private static final Path OPTIMIZATION_MIGRATION =
            Path.of("src/main/resources/db/migration/V079__optimize_ip_country_stats.sql");

    @Test
    void countryStatsUseNormalizedRegionAndMatchingCompositeIndex() throws IOException {
        String mapperSql = Files.readString(MAPPER_XML, StandardCharsets.UTF_8);
        String migrationSql = Files.readString(OPTIMIZATION_MIGRATION, StandardCharsets.UTF_8);

        assertThat(mapperSql)
                .contains("ON p.region = c.name_zh")
                .contains("AND region = #{region}")
                .doesNotContain("TRIM(p.region)", "TRIM(region)");
        assertThat(migrationSql)
                .contains("SET region = TRIM(region)")
                .contains("idx_ip_proxy_tenant_region_deleted_status")
                .contains("(tenant_id, region, deleted_at, status)");
    }
}
