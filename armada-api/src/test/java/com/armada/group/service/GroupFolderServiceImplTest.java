package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupFolderMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupFolderDTO;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderDeleteResultVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.service.impl.GroupFolderServiceImpl;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/** 群组列表运营分组业务规则单测。 */
@ExtendWith(MockitoExtension.class)
class GroupFolderServiceImplTest {

    @Mock
    private GroupFolderMapper folderMapper;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupConverter converter;

    private GroupFolderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GroupFolderServiceImpl(folderMapper, groupLinkMapper, converter);
    }

    @Test
    void createTrimsNameAndReturnsInsertedFolder() {
        when(folderMapper.insert(any())).thenAnswer(invocation -> {
            GroupFolder row = invocation.getArgument(0);
            row.setId(10L);
            return 1;
        });

        GroupFolderVO result = service.create(new GroupFolderDTO("  印度组  "));

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.name()).isEqualTo("印度组");
        assertThat(result.groupCount()).isZero();
        verify(folderMapper).selectActiveByName("印度组");
        verify(folderMapper).selectDeletedByName("印度组");
    }

    @Test
    void createRevivesSoftDeletedFolder() {
        GroupFolder deleted = folder(12L, "印度组");
        deleted.setCreatedAt(50L);
        deleted.setDeletedAt(60L);
        when(folderMapper.selectDeletedByName("印度组")).thenReturn(deleted);
        when(folderMapper.reviveById(eq(12L), anyLong())).thenReturn(1);

        GroupFolderVO result = service.create(new GroupFolderDTO("印度组"));

        assertThat(result.id()).isEqualTo(12L);
        assertThat(result.createdAt()).isEqualTo(50L);
        verify(folderMapper, never()).insert(any());
    }

    @Test
    void createRejectsBlankAndDuplicateNames() {
        assertThatThrownBy(() -> service.create(new GroupFolderDTO("   ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");

        when(folderMapper.selectActiveByName("印度组")).thenReturn(folder(1L, "印度组"));
        assertThatThrownBy(() -> service.create(new GroupFolderDTO("印度组")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("群组分组名称已存在");
    }

    @Test
    void createTranslatesUniqueKeyRaceToBusinessError() {
        when(folderMapper.insert(any())).thenThrow(new DuplicateKeyException("uq_group_folder_name"));

        assertThatThrownBy(() -> service.create(new GroupFolderDTO("印度组")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("群组分组名称已存在");
    }

    @Test
    void updateRejectsNameOwnedByAnotherSoftDeletedFolder() {
        when(folderMapper.selectById(10L)).thenReturn(folder(10L, "旧名称"));
        GroupFolder deleted = folder(11L, "新名称");
        deleted.setDeletedAt(100L);
        when(folderMapper.selectAnyByName("新名称")).thenReturn(deleted);

        assertThatThrownBy(() -> service.update(10L, new GroupFolderDTO(" 新名称 ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("群组分组名称已存在");
        verify(folderMapper, never()).updateName(anyLong(), any(), anyLong());
    }

    @Test
    void batchDeleteClearsGroupsBeforeSoftDeletingFolders() {
        GroupFolder folder = folder(10L, "印度组");
        when(folderMapper.selectActiveByIdsForUpdate(List.of(10L))).thenReturn(List.of(folder));
        when(groupLinkMapper.countActiveByFolderIds(List.of(10L))).thenReturn(3);
        when(groupLinkMapper.clearFolderByFolderIds(eq(List.of(10L)), anyLong())).thenReturn(3);
        when(folderMapper.softDeleteByIds(eq(List.of(10L)), anyLong())).thenReturn(1);

        GroupFolderDeleteResultVO result = service.batchDelete(List.of(10L, 10L));

        assertThat(result.deletedFolderCount()).isEqualTo(1);
        assertThat(result.ungroupedGroupCount()).isEqualTo(3);
        InOrder order = inOrder(groupLinkMapper, folderMapper);
        order.verify(groupLinkMapper).clearFolderByFolderIds(eq(List.of(10L)), anyLong());
        order.verify(folderMapper).softDeleteByIds(eq(List.of(10L)), anyLong());
    }

    @Test
    void batchDeleteRejectsMissingFolderWithoutClearingGroups() {
        when(folderMapper.selectActiveByIdsForUpdate(List.of(10L, 11L)))
                .thenReturn(List.of(folder(10L, "印度组")));

        assertThatThrownBy(() -> service.batchDelete(List.of(11L, 10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("部分群组分组不存在");
        verify(groupLinkMapper, never()).clearFolderByFolderIds(any(), anyLong());
        verify(folderMapper, never()).softDeleteByIds(any(), anyLong());
    }

    private static GroupFolder folder(Long id, String name) {
        GroupFolder folder = new GroupFolder();
        folder.setId(id);
        folder.setName(name);
        folder.setCreatedAt(10L);
        folder.setUpdatedAt(10L);
        return folder;
    }
}
