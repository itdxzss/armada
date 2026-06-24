package com.armada.marketing.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * 营销模板转换单测:重点验证时间字段(库内 UTC 墙钟)→ 出参 epoch 毫秒的口径正确。
 *
 * <p>服务器在孟买,但库表 {@code DATETIME} 配 {@code serverTimezone=UTC} 存的是 UTC 墙钟,
 * MyBatis 读成不带时区的 {@code LocalDateTime}。转换必须按 UTC 解释,否则每个时间会差 5.5/8 小时。</p>
 */
class MarketingTemplateConverterTest {

    private final MarketingTemplateConverter converter =
            Mappers.getMapper(MarketingTemplateConverter.class);

    /** 2024-01-01T00:00:00 按 UTC 解释 = 1704067200000 毫秒。 */
    private static final long EPOCH_2024_01_01_UTC = 1_704_067_200_000L;

    @Test
    void toEpochMilli_interpretsLocalDateTimeAsUtc() {
        assertThat(converter.toEpochMilli(LocalDateTime.of(2024, 1, 1, 0, 0, 0)))
                .isEqualTo(EPOCH_2024_01_01_UTC);
    }

    @Test
    void toEpochMilli_null_returnsNull() {
        assertThat(converter.toEpochMilli(null)).isNull();
    }

    @Test
    void toVO_mapsTimestampsToEpochMillis() {
        MarketingTemplate entity = new MarketingTemplate();
        entity.setId(1L);
        entity.setTemplateName("t");
        entity.setLinkMode(1);
        entity.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        MarketingTemplateVO vo = converter.toVO(entity);

        assertThat(vo.createdAt()).isEqualTo(EPOCH_2024_01_01_UTC);
        assertThat(vo.updatedAt()).isEqualTo(EPOCH_2024_01_01_UTC);
    }
}
