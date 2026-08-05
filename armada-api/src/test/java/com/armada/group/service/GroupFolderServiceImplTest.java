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
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.dto.GroupFolderWriteDTO;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.vo.GroupFolderDeleteVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.service.impl.GroupFolderServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/** 群组运营分组 Service 业务规则测试。 */
@ExtendWith(MockitoExtension.class)
class GroupFolderServiceImplTest {

    @Mock
    private GroupFolderMapper mapper;

    @InjectMocks
    private GroupFolderServiceImpl service;

    @Test
    void listSkipsPageQueryWhenTotalIsZero() {
        GroupFolderQuery query = new GroupFolderQuery();
        when(mapper.countPage(query)).thenReturn(0L);

        PageResult<GroupFolderVO> result = service.list(query);

        assertThat(result.total()).isZero();
        assertThat(result.list()).isEmpty();
        verify(mapper, never()).selectPage(any());
    }

    @Test
    void createTrimsNameAndStoresCurrentUser() {
        when(mapper.selectActiveByName("印度组")).thenReturn(null);
        when(mapper.selectDeletedByName("印度组")).thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            GroupFolder row = invocation.getArgument(0);
            row.setId(9L);
            return 1;
        }).when(mapper).insert(any());

        GroupFolderVO result = service.create(new GroupFolderWriteDTO("  印度组  "), 501L);

        var captor = org.mockito.ArgumentCaptor.forClass(GroupFolder.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("印度组");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(501L);
        assertThat(result.id()).isEqualTo(9L);
        assertThat(result.groupCount()).isZero();
    }

    @Test
    void createRevivesSoftDeletedNameInsteadOfInserting() {
        GroupFolder deleted = folder(7L, "印度组");
        deleted.setDeletedAt(90L);
        when(mapper.selectDeletedByName("印度组")).thenReturn(deleted);
        when(mapper.revive(any())).thenReturn(1);

        GroupFolderVO result = service.create(new GroupFolderWriteDTO("印度组"), 501L);

        verify(mapper).revive(any());
        verify(mapper, never()).insert(any());
        assertThat(result.id()).isEqualTo(7L);
    }

    @Test
    void createRejectsBlankOrOverlongName() {
        assertThatThrownBy(() -> service.create(new GroupFolderWriteDTO("  "), 501L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(
                new GroupFolderWriteDTO("x".repeat(101)), 501L))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).insert(any());
    }

    @Test
    void createTranslatesUniqueKeyRaceToBusinessError() {
        when(mapper.insert(any())).thenThrow(new DuplicateKeyException("uq_group_folder_name"));

        assertThatThrownBy(() -> service.create(new GroupFolderWriteDTO("印度组"), 501L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分组名称已存在");
    }

    @Test
    void updateRejectsAnotherActiveFolderWithSameName() {
        when(mapper.selectActiveById(1L)).thenReturn(folder(1L, "旧名"));
        when(mapper.selectAnyByName("冲突名")).thenReturn(folder(2L, "冲突名"));

        assertThatThrownBy(() -> service.update(1L, new GroupFolderWriteDTO("冲突名")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(mapper, never()).updateName(any());
    }

    @Test
    void updateRejectsNameOwnedBySoftDeletedFolder() {
        when(mapper.selectActiveById(1L)).thenReturn(folder(1L, "旧名"));
        GroupFolder deleted = folder(2L, "冲突名");
        deleted.setDeletedAt(300L);
        when(mapper.selectAnyByName("冲突名")).thenReturn(deleted);

        assertThatThrownBy(() -> service.update(1L, new GroupFolderWriteDTO("冲突名")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(mapper, never()).updateName(any());
    }

    @Test
    void batchDeleteDeduplicatesIdsAndReturnsBothAffectedCounts() {
        when(mapper.selectActiveByIdsForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(folder(1L, "一组"), folder(2L, "二组")));
        when(mapper.clearGroupLinks(eq(List.of(1L, 2L)), anyLong())).thenReturn(3);
        when(mapper.softDeleteByIds(eq(List.of(1L, 2L)), anyLong())).thenReturn(2);

        GroupFolderDeleteVO result = service.batchDelete(List.of(1L, 2L, 1L));

        assertThat(result.deletedFolderCount()).isEqualTo(2);
        assertThat(result.ungroupedGroupCount()).isEqualTo(3);
        InOrder order = inOrder(mapper);
        order.verify(mapper).selectActiveByIdsForUpdate(List.of(1L, 2L));
        order.verify(mapper).clearGroupLinks(eq(List.of(1L, 2L)), anyLong());
        order.verify(mapper).softDeleteByIds(eq(List.of(1L, 2L)), anyLong());
    }

    @Test
    void batchDeleteRejectsMissingFolderBeforeClearingGroups() {
        when(mapper.selectActiveByIdsForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(folder(1L, "一组")));

        assertThatThrownBy(() -> service.batchDelete(List.of(2L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("部分群组分组不存在");
        verify(mapper, never()).clearGroupLinks(any(), anyLong());
        verify(mapper, never()).softDeleteByIds(any(), anyLong());
    }

    @Test
    void requireExistingAndUsableLinksDoNotExposeGroupEntityAcrossDomain() {
        when(mapper.selectActiveById(8L)).thenReturn(folder(8L, "可用组"));
        when(mapper.selectUsableLinks(8L)).thenReturn(List.of("chat.whatsapp.com/AAA"));

        GroupFolderOptionVO snapshot = service.requireExisting(8L);

        assertThat(snapshot).isEqualTo(new GroupFolderOptionVO(8L, "可用组"));
        assertThat(service.usableLinks(8L)).containsExactly("chat.whatsapp.com/AAA");
    }

    @Test
    void requireExistingRejectsMissingFolder() {
        when(mapper.selectActiveById(8L)).thenReturn(null);

        assertThatThrownBy(() -> service.requireExisting(8L))
                .isInstanceOf(BusinessException.class);
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
