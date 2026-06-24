package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.model.vo.GroupLinkVoRow;
import com.armada.group.service.impl.GroupLinkServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * GroupLinkService 业务规则单测(mock mapper/converter,验证业务逻辑;SQL/真库另由 DbTest 覆盖)。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkServiceImplTest {

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupLinkLabelMapper labelMapper;

    @Mock
    private GroupConverter converter;

    @InjectMocks
    private GroupLinkServiceImpl service;

    // ---- listByLabel ----

    @Test
    void listByLabel_returnsEmptyPage_whenTotalZero() {
        GroupLinkQuery q = new GroupLinkQuery();
        q.setLabelId(1L);
        when(groupLinkMapper.countByLabel(q)).thenReturn(0L);

        PageResult<GroupLinkVO> result = service.listByLabel(q);

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.list()).isEmpty();
        verify(groupLinkMapper, never()).selectPageByLabel(any());
    }

    @Test
    void listByLabel_callsSelectPage_whenTotalNonZero() {
        GroupLinkQuery q = new GroupLinkQuery();
        q.setLabelId(1L);
        GroupLinkVoRow row = new GroupLinkVoRow();
        GroupLinkVO vo = new GroupLinkVO(1L, "https://chat.whatsapp.com/abc", "群A", "links.txt", 1000L);
        when(groupLinkMapper.countByLabel(q)).thenReturn(1L);
        when(groupLinkMapper.selectPageByLabel(q)).thenReturn(List.of(row));
        when(converter.toGroupLinkVOList(List.of(row))).thenReturn(List.of(vo));

        PageResult<GroupLinkVO> result = service.listByLabel(q);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.list()).hasSize(1);
        assertThat(result.list().get(0).url()).isEqualTo("https://chat.whatsapp.com/abc");
    }

    // ---- migrate ----

    @Test
    void migrate_nullIds_throws() {
        assertThatThrownBy(() -> service.migrate(null, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1.." + 100);
    }

    @Test
    void migrate_emptyIds_throws() {
        assertThatThrownBy(() -> service.migrate(List.of(), 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void migrate_exceedsMax_throws() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        assertThatThrownBy(() -> service.migrate(ids, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void migrate_nullTargetLabelId_throws() {
        assertThatThrownBy(() -> service.migrate(List.of(1L), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标分组 ID 不能为空");
    }

    @Test
    void migrate_targetLabelNotFound_throws() {
        when(labelMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.migrate(List.of(1L, 2L), 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标分组不存在");
        verify(groupLinkMapper, never()).migrateToLabel(any(), any());
    }

    @Test
    void migrate_someLinksInactive_throws() {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setId(5L);
        when(labelMapper.selectById(5L)).thenReturn(label);
        List<Long> ids = List.of(1L, 2L, 3L);
        when(groupLinkMapper.countActiveByIds(ids)).thenReturn(2); // 只有 2 个活跃,期望 3

        assertThatThrownBy(() -> service.migrate(ids, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("部分群链接不存在或已删除");
        verify(groupLinkMapper, never()).migrateToLabel(any(), any());
    }

    @Test
    void migrate_allActiveAndLabelExists_migrates() {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setId(5L);
        when(labelMapper.selectById(5L)).thenReturn(label);
        List<Long> ids = List.of(1L, 2L);
        when(groupLinkMapper.countActiveByIds(ids)).thenReturn(2);
        when(groupLinkMapper.migrateToLabel(ids, 5L)).thenReturn(2);

        int result = service.migrate(ids, 5L);

        assertThat(result).isEqualTo(2);
        verify(groupLinkMapper).migrateToLabel(ids, 5L);
    }

    // ---- batchDelete ----

    @Test
    void batchDelete_nullIds_throws() {
        assertThatThrownBy(() -> service.batchDelete(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_emptyIds_throws() {
        assertThatThrownBy(() -> service.batchDelete(List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_exceedsMax_throws() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        assertThatThrownBy(() -> service.batchDelete(ids))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_valid_softDeletes() {
        List<Long> ids = List.of(1L, 2L, 3L);
        when(groupLinkMapper.softDeleteByIds(ids)).thenReturn(3);

        int result = service.batchDelete(ids);

        assertThat(result).isEqualTo(3);
        verify(groupLinkMapper).softDeleteByIds(ids);
    }
}
