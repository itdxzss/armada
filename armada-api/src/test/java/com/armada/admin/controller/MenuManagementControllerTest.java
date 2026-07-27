package com.armada.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.admin.model.dto.MenuCreateDTO;
import com.armada.admin.model.dto.MenuUpdateDTO;
import com.armada.admin.model.dto.StatusUpdateDTO;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class MenuManagementControllerTest {

    @Test
    void exposesApprovedMenuManagementRoutes() throws Exception {
        RequestMapping root = MenuManagementController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/admin/menus");

        Method tree = MenuManagementController.class.getMethod("tree");
        Method create = MenuManagementController.class.getMethod("create", MenuCreateDTO.class);
        Method update = MenuManagementController.class.getMethod("update", long.class, MenuUpdateDTO.class);
        Method status = MenuManagementController.class.getMethod("changeStatus", long.class, StatusUpdateDTO.class);

        assertThat(tree.getAnnotation(GetMapping.class).value()).containsExactly("/tree");
        assertThat(create.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(update.getAnnotation(PutMapping.class).value()).containsExactly("/{id}");
        assertThat(status.getAnnotation(PatchMapping.class).value()).containsExactly("/{id}/status");
    }
}
