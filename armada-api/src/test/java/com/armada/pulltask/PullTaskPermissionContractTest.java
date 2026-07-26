package com.armada.pulltask;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertPermission("batchDelete", "tenant:pull_task:delete", java.util.Map.class,
                com.armada.shared.security.AuthPrincipal.class);
        assertPermission("export", "tenant:pull_task:export", Long.class,
                com.armada.shared.security.AuthPrincipal.class);
    }

    private static void assertPermission(String methodName, String permission, Class<?>... types)
            throws NoSuchMethodException {
        Method method = PullTaskController.class.getDeclaredMethod(methodName, types);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).contains(permission);
    }
}
