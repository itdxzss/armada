package com.armada.hyperlink.template.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 超链模板 Controller 冻结路径、HTTP 动词和权限合同测试。 */
class HyperlinkTemplateControllerContractTest {

    @Test
    void controllerUsesFrozenBasePathAndReadEndpoints() throws NoSuchMethodException {
        RequestMapping mapping = HyperlinkTemplateController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/hyperlink-templates");
        assertThat(preAuthorize(HyperlinkTemplateController.class)).isEqualTo(
                "hasAuthority('tenant:hyperlink_template:view')");

        assertThat(method("list", com.armada.hyperlink.template.model.dto.HyperlinkTemplateQuery.class)
                .getAnnotation(GetMapping.class).value()).isEmpty();
        assertThat(method("detail", Long.class).getAnnotation(GetMapping.class).value())
                .containsExactly("/{id}");
        assertThat(method("options", Integer.class, String.class, Integer.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/options");
    }

    @Test
    void writeEndpointsUseFrozenPathsAndDedicatedPermissions() throws NoSuchMethodException {
        Method create = method(
                "create",
                com.armada.hyperlink.template.model.dto.HyperlinkTemplateCreateDTO.class,
                com.armada.shared.security.AuthPrincipal.class);
        assertThat(create.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(preAuthorize(create)).contains("tenant:hyperlink_template:create");

        Method update = method(
                "update",
                Long.class,
                com.armada.hyperlink.template.model.dto.HyperlinkTemplateUpdateDTO.class);
        assertThat(update.getAnnotation(PutMapping.class).value()).containsExactly("/{id}");
        assertThat(preAuthorize(update)).contains("tenant:hyperlink_template:edit");

        Method copy = method("copy", Long.class, com.armada.shared.security.AuthPrincipal.class);
        assertThat(copy.getAnnotation(PostMapping.class).value()).containsExactly("/{id}/copy");
        assertThat(preAuthorize(copy)).contains("tenant:hyperlink_template:copy");

        Method delete = method("delete", Long.class);
        assertThat(delete.getAnnotation(DeleteMapping.class).value()).containsExactly("/{id}");
        assertThat(preAuthorize(delete)).contains("tenant:hyperlink_template:delete");
    }

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return HyperlinkTemplateController.class.getMethod(name, parameterTypes);
    }

    private static String preAuthorize(Method method) {
        return method.getAnnotation(PreAuthorize.class).value();
    }

    private static String preAuthorize(Class<?> type) {
        return type.getAnnotation(PreAuthorize.class).value();
    }
}
