package com.armada.pulltask;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.controller.PullTaskListController;
import com.armada.task.model.dto.PullTaskIdsDTO;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** 防止后续把拉群任务写接口误降级为仅校验页面查看权限。 */
class PullTaskPermissionContractTest {

    @Test
    void writeEndpointsUseIndependentPermissions() throws Exception {
        assertPermission("create", "tenant:pull_task:create", java.util.Map.class,
                com.armada.shared.security.AuthPrincipal.class);
        assertPermission("lifecycle", "tenant:pull_task:operate", Long.class,
                com.armada.shared.security.AuthPrincipal.class);
        assertPermission(PullTaskListController.class, "batchDelete",
                "tenant:pull_task:delete", PullTaskIdsDTO.class);
        assertPermission("export", "tenant:pull_task:export", Long.class,
                com.armada.shared.security.AuthPrincipal.class);
    }

    private static void assertPermission(String methodName, String permission, Class<?>... types)
            throws NoSuchMethodException {
        assertPermission(PullTaskController.class, methodName, permission, types);
    }

    private static void assertPermission(
            Class<?> controllerType,
            String methodName,
            String permission,
            Class<?>... types) throws NoSuchMethodException {
        Method method = controllerType.getDeclaredMethod(methodName, types);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains(permission);
    }
}
