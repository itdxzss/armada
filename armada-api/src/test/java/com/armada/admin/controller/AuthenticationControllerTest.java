package com.armada.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.admin.model.dto.PasswordChangeDTO;
import com.armada.shared.security.AuthPrincipal;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

class AuthenticationControllerTest {

    @Test
    void exposesAuthenticatedPasswordChangeRoute() throws Exception {
        Method method = AuthenticationController.class.getMethod(
                "changePassword", AuthPrincipal.class, PasswordChangeDTO.class);

        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/api/auth/change-password");
    }
}
