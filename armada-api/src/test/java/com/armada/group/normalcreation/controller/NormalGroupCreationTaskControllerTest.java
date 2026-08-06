package com.armada.group.normalcreation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.normalcreation.model.dto.NormalGroupCreationCreateDTO;
import com.armada.shared.security.AuthPrincipal;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class NormalGroupCreationTaskControllerTest {

    @Test
    void eachEndpointUsesItsOwnLeastPrivilegeAuthority() throws NoSuchMethodException {
        assertAuthority(
                "tenant:normal_group:create",
                "create",
                String.class,
                NormalGroupCreationCreateDTO.class,
                AuthPrincipal.class);
        assertAuthority("tenant:normal_group:view", "detail", long.class);
        assertAuthority(
                "tenant:normal_group:retry",
                "retry",
                long.class,
                long.class,
                AuthPrincipal.class);
    }

    private static void assertAuthority(
            String authority, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = NormalGroupCreationTaskController.class
                .getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasAuthority('" + authority + "')");
    }
}
