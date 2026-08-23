package com.armada.group.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.group.model.dto.GroupFolderWriteDTO;
import com.armada.group.model.vo.GroupFolderDeleteVO;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.model.vo.GroupFolderVO;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 群组列表运营分组 Controller 路由与参数契约测试。 */
@ExtendWith(MockitoExtension.class)
class GroupFolderControllerTest {

    @Mock
    private GroupFolderService service;

    private GroupFolderController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new GroupFolderController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void exposesFolderListAndOptions() throws Exception {
        when(service.list(argThat(query -> query.getPage() == 2 && query.getPageSize() == 20)))
                .thenReturn(PageResult.of(List.of(), 2, 20, 0));
        when(service.options()).thenReturn(List.of(new GroupFolderOptionVO(8L, "印度组")));

        mockMvc.perform(get("/api/group-folders?page=2&pageSize=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2));
        mockMvc.perform(get("/api/group-folders/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("印度组"));
    }

    @Test
    void createUsesAuthenticatedUserAndReturnsCreatedFolder() {
        GroupFolderWriteDTO request = new GroupFolderWriteDTO("印度组");
        GroupFolderVO created = new GroupFolderVO(8L, "印度组", false, 0L, 100L, 100L);
        when(service.create(request, 501L)).thenReturn(created);

        assertThat(controller.create(request, principal()).data()).isEqualTo(created);
        verify(service).create(request, 501L);
    }

    @Test
    void delegatesUpdateAndBatchDeleteRoutes() throws Exception {
        when(service.batchDelete(List.of(8L)))
                .thenReturn(new GroupFolderDeleteVO(1, 3));

        mockMvc.perform(patch("/api/group-folders/8")
                        .contentType("application/json")
                        .content("{\"name\":\"印度组-新\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/group-folders/batch-delete")
                        .contentType("application/json")
                        .content("{\"ids\":[8]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ungroupedGroupCount").value(3));

        verify(service).update(eq(8L), argThat(dto -> "印度组-新".equals(dto.name())));
        verify(service).batchDelete(List.of(8L));
    }

    @Test
    void allowsPullTaskViewOnlyForReadEndpoints() throws Exception {
        PreAuthorize controllerPermission =
                GroupFolderController.class.getAnnotation(PreAuthorize.class);
        Method list = GroupFolderController.class.getMethod(
                "list", com.armada.group.model.dto.GroupFolderQuery.class);
        Method options = GroupFolderController.class.getMethod("options");

        assertThat(controllerPermission.value())
                .isEqualTo("hasAuthority('tenant:group_link:view')");
        assertThat(list.getAnnotation(PreAuthorize.class).value())
                .contains("tenant:pull_task:view");
        assertThat(options.getAnnotation(PreAuthorize.class).value())
                .contains("tenant:pull_task:view");
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(
                501L, 7L, "wang", "小王", "tenant-7", "租户七", List.of(), List.of());
    }
}
