package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupLinkImportBatchMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupLinkLabelDTO;
import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkLabelVO;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
import com.armada.group.service.impl.GroupLinkLabelServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * GroupLinkLabelService 业务规则单测(mock mapper/converter,验证业务逻辑;SQL/真库另由 DbTest 覆盖)。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkLabelServiceImplTest {

    @Mock
    private GroupLinkLabelMapper labelMapper;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupLinkImportBatchMapper importBatchMapper;

    @Mock
    private GroupConverter converter;

    @InjectMocks
    private GroupLinkLabelServiceImpl service;

    // ---- list ----

    @Test
    void list_returnsEmptyPage_whenTotalZero() {
        GroupLinkLabelQuery q = new GroupLinkLabelQuery();
        when(labelMapper.countPage(q)).thenReturn(0L);

        PageResult<GroupLinkLabelVO> result = service.list(q);

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.list()).isEmpty();
        verify(labelMapper, never()).selectPage(any());
    }

    @Test
    void list_callsSelectPage_whenTotalNonZero() {
        GroupLinkLabelQuery q = new GroupLinkLabelQuery();
        GroupLinkLabelVoRow row = new GroupLinkLabelVoRow();
        GroupLinkLabelVO vo = new GroupLinkLabelVO(1L, "分组A", null, null, 0L, null, null);
        when(labelMapper.countPage(q)).thenReturn(1L);
        when(labelMapper.selectPage(q)).thenReturn(List.of(row));
        when(converter.toLabelVOList(List.of(row))).thenReturn(List.of(vo));

        PageResult<GroupLinkLabelVO> result = service.list(q);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.list()).hasSize(1);
    }

    // ---- create ----

    @Test
    void create_blankName_throws() {
        assertThatThrownBy(() -> service.create(new GroupLinkLabelDTO("  ", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称不能为空");
        verify(labelMapper, never()).insert(any());
    }

    @Test
    void create_activeNameExists_throws() {
        GroupLinkLabel existing = new GroupLinkLabel();
        existing.setId(99L);
        when(labelMapper.selectActiveByName("已存在")).thenReturn(existing);

        assertThatThrownBy(() -> service.create(new GroupLinkLabelDTO("已存在", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(labelMapper, never()).insert(any());
    }

    @Test
    void create_deletedNameExists_reviveAndUpdate_notInsert() {
        when(labelMapper.selectActiveByName("复活分组")).thenReturn(null);
        GroupLinkLabel deleted = new GroupLinkLabel();
        deleted.setId(10L);
        deleted.setName("复活分组");
        when(labelMapper.selectDeletedByName("复活分组")).thenReturn(deleted);
        // 读回行:含真实时间戳
        GroupLinkLabel saved = new GroupLinkLabel();
        saved.setId(10L);
        saved.setName("复活分组");
        saved.setRegion("印度");
        saved.setCreatedAt(1_704_067_200_000L);
        saved.setUpdatedAt(1_717_243_200_000L);
        when(labelMapper.selectById(10L)).thenReturn(saved);

        GroupLinkLabelVO vo = service.create(new GroupLinkLabelDTO("复活分组", "印度", "备注"));

        verify(labelMapper).reviveById(eq(10L), anyLong());
        verify(labelMapper).updateProfile(any());
        verify(labelMapper, never()).insert(any());
        assertThat(vo.id()).isEqualTo(10L);
        assertThat(vo.createdAt()).isNotNull();
        assertThat(vo.updatedAt()).isNotNull();
    }

    @Test
    void create_newName_inserts() {
        when(labelMapper.selectActiveByName("新分组")).thenReturn(null);
        when(labelMapper.selectDeletedByName("新分组")).thenReturn(null);
        // insert 填充自增 id(doAnswer 模拟 MyBatis useGeneratedKeys)
        org.mockito.Mockito.doAnswer(inv -> {
            GroupLinkLabel row = inv.getArgument(0);
            row.setId(99L);
            return 1;
        }).when(labelMapper).insert(any());
        // 读回行:含真实时间戳
        GroupLinkLabel saved = new GroupLinkLabel();
        saved.setId(99L);
        saved.setName("新分组");
        saved.setRegion("巴基斯坦");
        saved.setCreatedAt(1_717_200_000_000L);
        saved.setUpdatedAt(1_717_200_000_000L);
        when(labelMapper.selectById(99L)).thenReturn(saved);

        GroupLinkLabelVO vo = service.create(new GroupLinkLabelDTO("新分组", "巴基斯坦", null));

        verify(labelMapper).insert(any());
        assertThat(vo.name()).isEqualTo("新分组");
        assertThat(vo.createdAt()).isNotNull();
        assertThat(vo.updatedAt()).isNotNull();
    }

    // ---- update ----

    @Test
    void update_blankName_throws() {
        assertThatThrownBy(() -> service.update(1L, new GroupLinkLabelDTO("  ", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称不能为空");
        verify(labelMapper, never()).selectById(any());
        verify(labelMapper, never()).updateProfile(any());
    }

    @Test
    void update_notFound_throws() {
        when(labelMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(42L, new GroupLinkLabelDTO("X", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        verify(labelMapper, never()).updateProfile(any());
    }

    @Test
    void update_conflictingName_throws() {
        GroupLinkLabel cur = new GroupLinkLabel();
        cur.setId(1L);
        when(labelMapper.selectById(1L)).thenReturn(cur);

        GroupLinkLabel other = new GroupLinkLabel();
        other.setId(2L);
        when(labelMapper.selectActiveByName("冲突名")).thenReturn(other);

        assertThatThrownBy(() -> service.update(1L, new GroupLinkLabelDTO("冲突名", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(labelMapper, never()).updateProfile(any());
    }

    @Test
    void update_sameName_allowsSelf() {
        GroupLinkLabel cur = new GroupLinkLabel();
        cur.setId(1L);
        when(labelMapper.selectById(1L)).thenReturn(cur);
        // 查到的是自身
        GroupLinkLabel self = new GroupLinkLabel();
        self.setId(1L);
        when(labelMapper.selectActiveByName("同名")).thenReturn(self);

        service.update(1L, new GroupLinkLabelDTO("同名", "印度", null));

        verify(labelMapper).updateProfile(any());
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
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101)
                .boxed().toList();
        assertThatThrownBy(() -> service.batchDelete(ids))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_valid_cascadeSoftDelete() {
        List<Long> ids = List.of(1L, 2L);
        when(labelMapper.softDeleteByIds(eq(ids), anyLong())).thenReturn(2);

        int result = service.batchDelete(ids);

        verify(groupLinkMapper).softDeleteByLabelIds(eq(ids), anyLong());
        verify(importBatchMapper).softDeleteByLabelIds(eq(ids), anyLong());
        verify(labelMapper).softDeleteByIds(eq(ids), anyLong());
        assertThat(result).isEqualTo(2);
    }
}
