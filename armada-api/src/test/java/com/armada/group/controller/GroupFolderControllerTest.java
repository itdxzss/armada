package com.armada.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.dto.GroupFolderWriteDTO;
import com.armada.group.model.dto.GroupIdsDTO;
import com.armada.group.model.vo.GroupFolderDeleteVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 群组运营分组 Controller 参数衔接测试。 */
class GroupFolderControllerTest {

    private final GroupFolderService service = mock(GroupFolderService.class);
    private final GroupFolderController controller = new GroupFolderController(service);

    @Test
    void delegatesEveryFrontendContractEndpoint() {
        GroupFolderQuery query = new GroupFolderQuery();
        GroupFolderVO row = new GroupFolderVO(7L, "印度组", 3L, 100L, 200L);
        PageResult<GroupFolderVO> page = PageResult.of(List.of(row), 1, 10, 1);
        List<GroupFolderOptionVO> options = List.of(new GroupFolderOptionVO(7L, "印度组"));
        GroupFolderWriteDTO write = new GroupFolderWriteDTO("印度组");
        GroupFolderDeleteVO deleted = new GroupFolderDeleteVO(1, 3);
        when(service.list(query)).thenReturn(page);
        when(service.options()).thenReturn(options);
        when(service.create(write, 501L)).thenReturn(row);
        when(service.batchDelete(List.of(7L))).thenReturn(deleted);

        assertThat(controller.list(query).data()).isEqualTo(page);
        assertThat(controller.options().data()).isEqualTo(options);
        assertThat(controller.create(write, principal()).data()).isEqualTo(row);
        assertThat(controller.update(7L, write).data()).isNull();
        assertThat(controller.batchDelete(new GroupIdsDTO(List.of(7L))).data())
                .isEqualTo(deleted);

        verify(service).update(7L, write);
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(
                501L, 7L, "wang", "小王", "tenant-7", "租户七", List.of(), List.of());
    }
}
