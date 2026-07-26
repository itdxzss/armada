package com.armada.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.admin.model.dto.RoleCreateDTO;
import com.armada.admin.model.dto.RoleMenuGrantDTO;
import com.armada.admin.model.dto.RoleUpdateDTO;
import com.armada.admin.model.dto.StatusUpdateDTO;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class RoleManagementControllerTest {

    @Test
    void exposesApprovedRoleManagementRoutes() throws Exception {
        RequestMapping root = RoleManagementController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/admin/roles");

        Method list = RoleManagementController.class.getMethod("list");
        Method create = RoleManagementController.class.getMethod("create", RoleCreateDTO.class);
        Method update = RoleManagementController.class.getMethod("update", long.class, RoleUpdateDTO.class);
        Method status = RoleManagementController.class.getMethod("changeStatus", long.class, StatusUpdateDTO.class);
        Method getMenus = RoleManagementController.class.getMethod("getMenuIds", long.class);
        Method putMenus = RoleManagementController.class.getMethod("replaceMenus", long.class, RoleMenuGrantDTO.class);

        assertThat(list.getAnnotation(GetMapping.class).value()).isEmpty();
        assertThat(create.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(update.getAnnotation(PutMapping.class).value()).containsExactly("/{id}");
        assertThat(status.getAnnotation(PatchMapping.class).value()).containsExactly("/{id}/status");
        assertThat(getMenus.getAnnotation(GetMapping.class).value()).containsExactly("/{id}/menus");
        assertThat(putMenus.getAnnotation(PutMapping.class).value()).containsExactly("/{id}/menus");
    }
}
