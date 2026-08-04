package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    void updateRejectsAnotherActiveFolderWithSameName() {
        when(mapper.selectActiveById(1L)).thenReturn(folder(1L, "旧名"));
        when(mapper.selectActiveByName("冲突名")).thenReturn(folder(2L, "冲突名"));

        assertThatThrownBy(() -> service.update(1L, new GroupFolderWriteDTO("冲突名")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(mapper, never()).updateName(any());
    }

    @Test
    void batchDeleteDeduplicatesIdsAndReturnsBothAffectedCounts() {
        when(mapper.clearGroupLinks(eq(List.of(1L, 2L)), anyLong())).thenReturn(3);
        when(mapper.softDeleteByIds(eq(List.of(1L, 2L)), anyLong())).thenReturn(2);

        GroupFolderDeleteVO result = service.batchDelete(List.of(1L, 2L, 1L));

        assertThat(result.deletedFolderCount()).isEqualTo(2);
        assertThat(result.ungroupedGroupCount()).isEqualTo(3);
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
