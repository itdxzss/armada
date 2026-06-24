package com.armada.group.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.vo.GroupLinkLabelVoRow;
import com.armada.group.model.vo.GroupLinkLabelVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * GroupConverter 单测:验时间字段(库内 UTC 墙钟)→ 出参 epoch 毫秒的口径正确。
 */
class GroupConverterTest {

    private final GroupConverter converter = Mappers.getMapper(GroupConverter.class);

    /** 2024-06-01T00:00:00 按 UTC 解释 = 1717200000000 毫秒。 */
    private static final long EPOCH_2024_06_01_UTC = LocalDateTime.of(2024, 6, 1, 0, 0, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli();

    @Test
    void toLabelVO_epochMillis() {
        LocalDateTime t = LocalDateTime.of(2024, 6, 1, 0, 0, 0);
        GroupLinkLabelVoRow row = new GroupLinkLabelVoRow();
        row.setId(1L);
        row.setName("印度");
        row.setRegion("印度");
        row.setRemark("r");
        row.setLinkCount(5L);
        row.setCreatedAt(t);
        row.setUpdatedAt(t);

        GroupLinkLabelVO vo = converter.toLabelVO(row);

        assertThat(vo.createdAt()).isEqualTo(EPOCH_2024_06_01_UTC);
        assertThat(vo.updatedAt()).isEqualTo(EPOCH_2024_06_01_UTC);
        assertThat(vo.linkCount()).isEqualTo(5L);
        assertThat(vo.name()).isEqualTo("印度");
    }

    @Test
    void toEpochMilli_null_returnsNull() {
        assertThat(converter.toEpochMilli(null)).isNull();
    }

    @Test
    void toEpochMilli_interpretsAsUtc() {
        assertThat(converter.toEpochMilli(LocalDateTime.of(2024, 6, 1, 0, 0, 0)))
                .isEqualTo(EPOCH_2024_06_01_UTC);
    }
}
