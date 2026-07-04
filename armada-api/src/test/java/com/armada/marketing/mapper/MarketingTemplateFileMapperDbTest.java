package com.armada.marketing.mapper;

import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 营销模板图片文件 Mapper 真库测试。
 */
class MarketingTemplateFileMapperDbTest extends DbTestBase {

    @Autowired
    private MarketingTemplateFileMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void insertAndSelect_roundTripsImageBytesAndTenantId() {
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setOriginalFilename("promo.png");
        file.setContentType("image/png");
        file.setSizeBytes(3L);
        file.setContent(new byte[] {1, 2, 3});
        file.setCreatedAt(System.currentTimeMillis());

        int inserted = mapper.insert(file);

        assertThat(inserted).isEqualTo(1);
        assertThat(file.getId()).isPositive();
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM marketing_template_file WHERE id = ?",
                Long.class,
                file.getId());
        assertThat(tenantId).isEqualTo(TEST_TENANT_ID);

        MarketingTemplateFile found = mapper.selectById(file.getId());
        assertThat(found).isNotNull();
        assertThat(found.getOriginalFilename()).isEqualTo("promo.png");
        assertThat(found.getContentType()).isEqualTo("image/png");
        assertThat(found.getSizeBytes()).isEqualTo(3L);
        assertThat(found.getContent()).containsExactly(1, 2, 3);
    }
}
