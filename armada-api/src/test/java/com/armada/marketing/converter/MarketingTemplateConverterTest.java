package com.armada.marketing.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** 营销模板转换单测:时间字段已是 epoch 毫秒,转换层直映。 */
class MarketingTemplateConverterTest {

    private final MarketingTemplateConverter converter =
            Mappers.getMapper(MarketingTemplateConverter.class);

    /** 2024-01-01T00:00:00 按 UTC 解释 = 1704067200000 毫秒。 */
    private static final long EPOCH_2024_01_01_UTC = 1_704_067_200_000L;

    @Test
    void toVO_keepsEpochMillis() {
        MarketingTemplate entity = new MarketingTemplate();
        entity.setId(1L);
        entity.setTemplateName("t");
        entity.setLinkMode(1);
        entity.setCreatedAt(EPOCH_2024_01_01_UTC);
        entity.setUpdatedAt(EPOCH_2024_01_01_UTC);

        MarketingTemplateVO vo = converter.toVO(entity);

        assertThat(vo.createdAt()).isEqualTo(EPOCH_2024_01_01_UTC);
        assertThat(vo.updatedAt()).isEqualTo(EPOCH_2024_01_01_UTC);
    }
}
