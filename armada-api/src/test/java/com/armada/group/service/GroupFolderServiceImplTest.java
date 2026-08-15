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

import com.armada.group.mapper.GroupFolderMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.dto.GroupFolderWriteDTO;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderDeleteVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.service.impl.GroupFolderServiceImpl;
import com.armada.group.service.impl.GroupCurrentLocalPersistence;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/** 群组运营分组 Service 业务规则测试。 */
@ExtendWith(MockitoExtension.class)
class GroupFolderServiceImplTest {

    @Mock
    private GroupFolderMapper folderMapper;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupCurrentLocalPersistence currentLocalPersistence;

    private GroupFolderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GroupFolderServiceImpl(
                folderMapper, groupLinkMapper, currentLocalPersistence);
    }

    @Test
    void listSkipsPageQueryWhenTotalIsZero() {
        GroupFolderQuery query = new GroupFolderQuery();
        when(folderMapper.countPage(query)).thenReturn(0L);

        PageResult<GroupFolderVO> result = service.list(query);

        assertThat(result.total()).isZero();
        assertThat(result.list()).isEmpty();
        verify(folderMapper, never()).selectPage(any());
    }

    @Test
    void createTrimsNameAndStoresCurrentUser() {
        when(folderMapper.insert(any())).thenAnswer(invocation -> {
            GroupFolder row = invocation.getArgument(0);
            row.setId(9L);
            return 1;
        });

        GroupFolderVO result = service.create(new GroupFolderWriteDTO("  印度组  "), 501L);

        var captor = org.mockito.ArgumentCaptor.forClass(GroupFolder.class);
        verify(folderMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("印度组");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(501L);
        assertThat(result.id()).isEqualTo(9L);
        assertThat(result.groupCount()).isZero();
        verify(folderMapper).selectActiveByName("印度组");
        verify(folderMapper).selectDeletedByName("印度组");
    }

    @Test
    void createRevivesSoftDeletedNameInsteadOfInserting() {
        GroupFolder deleted = folder(7L, "印度组");
        deleted.setDeletedAt(90L);
        when(folderMapper.selectDeletedByName("印度组")).thenReturn(deleted);
        when(folderMapper.revive(any())).thenReturn(1);

        GroupFolderVO result = service.create(new GroupFolderWriteDTO("印度组"), 501L);

        var captor = org.mockito.ArgumentCaptor.forClass(GroupFolder.class);
        verify(folderMapper).revive(captor.capture());
        verify(folderMapper, never()).insert(any());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(501L);
        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.createdAt()).isEqualTo(100L);
    }

    @Test
    void createRejectsBlankOverlongAndDuplicateNames() {
        assertThatThrownBy(() -> service.create(new GroupFolderWriteDTO("  "), 501L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.create(
                new GroupFolderWriteDTO("x".repeat(101)), 501L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("100");

        when(folderMapper.selectActiveByName("印度组"))
                .thenReturn(folder(1L, "印度组"));
        assertThatThrownBy(() -> service.create(new GroupFolderWriteDTO("印度组"), 501L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(folderMapper, never()).insert(any());
    }

    @Test
    void createTranslatesUniqueKeyRaceToBusinessError() {
        when(folderMapper.insert(any()))
                .thenThrow(new DuplicateKeyException("uq_group_folder_name"));

        assertThatThrownBy(() -> service.create(new GroupFolderWriteDTO("印度组"), 501L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void updateRejectsNameOwnedByAnotherSoftDeletedFolder() {
        when(folderMapper.selectById(10L)).thenReturn(folder(10L, "旧名称"));
        GroupFolder deleted = folder(11L, "新名称");
        deleted.setDeletedAt(200L);
        when(folderMapper.selectAnyByName("新名称")).thenReturn(deleted);

        assertThatThrownBy(() -> service.update(
                10L, new GroupFolderWriteDTO(" 新名称 ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(folderMapper, never()).updateName(anyLong(), any(), anyLong());
    }

    @Test
    void batchDeleteLocksFoldersClearsGroupsAndThenSoftDeletes() {
        List<Long> normalizedIds = List.of(1L, 2L);
        when(folderMapper.selectActiveByIdsForUpdate(normalizedIds))
                .thenReturn(List.of(folder(1L, "一组"), folder(2L, "二组")));
        when(groupLinkMapper.countActiveByFolderIds(normalizedIds)).thenReturn(3);
        when(groupLinkMapper.clearFolderByFolderIds(eq(normalizedIds), anyLong())).thenReturn(3);
        when(folderMapper.softDeleteByIds(eq(normalizedIds), anyLong())).thenReturn(2);

        GroupFolderDeleteVO result = service.batchDelete(List.of(2L, 1L, 2L));

        assertThat(result.deletedFolderCount()).isEqualTo(2);
        assertThat(result.ungroupedGroupCount()).isEqualTo(3);
        InOrder order = inOrder(groupLinkMapper, folderMapper);
        order.verify(groupLinkMapper).clearFolderByFolderIds(eq(normalizedIds), anyLong());
        order.verify(folderMapper).softDeleteByIds(eq(normalizedIds), anyLong());
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

    @Test
    void batchDeleteRejectsConcurrentFolderRelationshipChange() {
        when(folderMapper.selectActiveByIdsForUpdate(List.of(10L)))
                .thenReturn(List.of(folder(10L, "印度组")));
        when(groupLinkMapper.countActiveByFolderIds(List.of(10L))).thenReturn(3);
        when(groupLinkMapper.clearFolderByFolderIds(eq(List.of(10L)), anyLong()))
                .thenReturn(2);

        assertThatThrownBy(() -> service.batchDelete(List.of(10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("关系已变化");
        verify(folderMapper, never()).softDeleteByIds(any(), anyLong());
    }

    @Test
    void requireExistingAndUsableLinksDoNotExposeGroupEntityAcrossDomain() {
        when(folderMapper.selectById(8L)).thenReturn(folder(8L, "可用组"));
        when(folderMapper.selectUsableLinks(8L))
                .thenReturn(List.of("chat.whatsapp.com/AAA"));

        GroupFolderOptionVO snapshot = service.requireExisting(8L);

        assertThat(snapshot).isEqualTo(new GroupFolderOptionVO(8L, "可用组"));
        assertThat(service.usableLinks(8L))
                .containsExactly("chat.whatsapp.com/AAA");
    }

    @Test
    void requireExistingRejectsInvalidOrMissingFolder() {
        assertThatThrownBy(() -> service.requireExisting(0L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正整数");
        when(folderMapper.selectById(8L)).thenReturn(null);
        assertThatThrownBy(() -> service.requireExisting(8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    private static GroupFolder folder(long id, String name) {
        GroupFolder row = new GroupFolder();
        row.setId(id);
        row.setName(name);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }
}
