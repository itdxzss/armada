package com.armada.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.admin.model.dto.PasswordResetDTO;
import com.armada.admin.model.dto.StatusUpdateDTO;
import com.armada.admin.model.dto.UserCreateDTO;
import com.armada.admin.model.dto.UserUpdateDTO;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class UserManagementControllerTest {

    @Test
    void exposesApprovedUserManagementRoutes() throws Exception {
        RequestMapping root = UserManagementController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/admin/users");

        Method list = UserManagementController.class.getMethod("list");
        Method get = UserManagementController.class.getMethod("get", long.class);
        Method create = UserManagementController.class.getMethod("create", UserCreateDTO.class);
        Method update = UserManagementController.class.getMethod("update", long.class, UserUpdateDTO.class);
        Method reset = UserManagementController.class.getMethod("resetPassword", long.class, PasswordResetDTO.class);
        Method status = UserManagementController.class.getMethod("changeStatus", long.class, StatusUpdateDTO.class);

        assertThat(list.getAnnotation(GetMapping.class).value()).isEmpty();
        assertThat(get.getAnnotation(GetMapping.class).value()).containsExactly("/{id}");
        assertThat(create.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(update.getAnnotation(PutMapping.class).value()).containsExactly("/{id}");
        assertThat(reset.getAnnotation(PostMapping.class).value()).containsExactly("/{id}/reset-password");
        assertThat(status.getAnnotation(PatchMapping.class).value()).containsExactly("/{id}/status");
    }
}
