package com.armada.hyperlink.strategy.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyCreateDTO;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyQuery;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyUpdateDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.shared.security.AuthPrincipal;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 超链策略 Controller 路径、HTTP 动词和跨页面消费权限合同。 */
class HyperlinkStrategyControllerContractTest {

    @Test
    void resourceEndpointsUseFrozenPathsAndPermissions() throws Exception {
        RequestMapping mapping = HyperlinkStrategyController.class
                .getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/hyperlink-strategies");

        Method list = method("list", HyperlinkStrategyQuery.class);
        assertThat(list.getAnnotation(GetMapping.class).value()).isEmpty();
        assertThat(permission(list)).contains("tenant:hyperlink_strategy:view");

        Method detail = method("detail", Long.class);
        assertThat(detail.getAnnotation(GetMapping.class).value()).containsExactly("/{id}");
        assertThat(permission(detail)).contains("tenant:hyperlink_strategy:view");

        Method create = method("create", HyperlinkStrategyCreateDTO.class, AuthPrincipal.class);
        assertThat(create.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(permission(create)).contains("tenant:hyperlink_strategy:create");

        Method update = method("update", Long.class, HyperlinkStrategyUpdateDTO.class);
        assertThat(update.getAnnotation(PutMapping.class).value()).containsExactly("/{id}");
        assertThat(permission(update)).contains("tenant:hyperlink_strategy:edit");

        Method delete = method("delete", Long.class);
        assertThat(delete.getAnnotation(DeleteMapping.class).value()).containsExactly("/{id}");
        assertThat(permission(delete)).contains("tenant:hyperlink_strategy:delete");
    }

    @Test
    void optionsCanBeConsumedByTaskEditorsAndAccountHelpersDoNotUseTaskPermission() throws Exception {
        Method options = method("options", String.class, Integer.class);
        assertThat(options.getAnnotation(GetMapping.class).value()).containsExactly("/options");
        assertThat(permission(options)).contains(
                "tenant:hyperlink_strategy:view",
                "tenant:hyperlink_task:create",
                "tenant:hyperlink_task:edit");

        Method context = method("accountContext");
        assertThat(context.getAnnotation(GetMapping.class).value())
                .containsExactly("/account-context");
        assertThat(permission(context)).contains(
                "tenant:hyperlink_strategy:create", "tenant:hyperlink_strategy:edit");

        Method count = method("accountMatchCount", HyperlinkAccountFilterDTO.class);
        assertThat(count.getAnnotation(PostMapping.class).value())
                .containsExactly("/account-match-count");
        assertThat(permission(count)).contains(
                "tenant:hyperlink_strategy:create", "tenant:hyperlink_strategy:edit");
    }

    private static Method method(String name, Class<?>... parameterTypes) throws Exception {
        return HyperlinkStrategyController.class.getMethod(name, parameterTypes);
    }

    private static String permission(Method method) {
        return method.getAnnotation(PreAuthorize.class).value();
    }
}
