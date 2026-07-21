package com.armada.promotion.template.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.promotion.template.model.dto.PromotionTemplateQuery;
import com.armada.promotion.template.model.vo.PromotionTemplateRow;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V060 固定模板与 MyBatis 租户隔离真库测试。 */
class PromotionTemplateMapperDbTest extends DbTestBase {

    private static final List<Long> SEEDED_IDS = List.of(130L, 40L, 39L, 38L, 37L);

    @Autowired
    private PromotionTemplateMapper mapper;

    @Test
    void tenantOneSeesFiveSeedTemplatesAndOtherTenantCannotSeeThem() {
        PromotionTemplateQuery query = new PromotionTemplateQuery();
        query.setPageSize(1000);

        List<PromotionTemplateRow> tenantOneRows = mapper.selectPage(query);
        long tenantOneTotal = mapper.countPage(query);
        assertThat(tenantOneRows)
                .filteredOn(row -> SEEDED_IDS.contains(row.getId()))
                .extracting(PromotionTemplateRow::getId)
                .containsExactly(130L, 40L, 39L, 38L, 37L);
        assertThat(tenantOneRows)
                .filteredOn(row -> SEEDED_IDS.contains(row.getId()))
                .allSatisfy(row -> assertThat(row.getIsSubaccountVisible()).isEqualTo(1));
        assertThat(tenantOneTotal).isGreaterThanOrEqualTo(SEEDED_IDS.size());

        try {
            TenantContext.set(2L);
            List<PromotionTemplateRow> tenantTwoRows = mapper.selectPage(query);
            long tenantTwoTotal = mapper.countPage(query);
            assertThat(tenantTwoRows)
                    .extracting(PromotionTemplateRow::getId)
                    .doesNotContainAnyElementsOf(SEEDED_IDS);
            assertThat(tenantTwoTotal).isEqualTo(tenantTwoRows.size());
        } finally {
            TenantContext.set(TEST_TENANT_ID);
        }
    }
}
