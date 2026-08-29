package com.armada.marketing.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.asset.controller.ResourceAssetController;
import com.armada.marketing.asset.model.dto.ResourceAssetQuery;
import com.armada.marketing.asset.model.dto.ResourceAssetUpdateDTO;
import com.armada.shared.security.AuthPrincipal;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;

/** 图片素材 API 路径和独立管理权限合同测试。 */
class ResourceAssetControllerContractTest {

    @Test
    void controllerExposesFrozenBasePathAndCrudAuthorities() throws Exception {
        RequestMapping mapping = ResourceAssetController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/resource-assets");

        assertThat(permission("list", ResourceAssetQuery.class)).contains("tenant:resource_asset:view");
        assertThat(permission("upload", MultipartFile.class, String.class, AuthPrincipal.class))
                .contains("tenant:resource_asset:upload", "tenant:hyperlink_template:create");
        assertThat(permission("update", Long.class, ResourceAssetUpdateDTO.class))
                .isEqualTo("hasAuthority('tenant:resource_asset:edit')");
        assertThat(permission("delete", Long.class))
                .isEqualTo("hasAuthority('tenant:resource_asset:delete')");
    }

    private static String permission(String name, Class<?>... parameterTypes) throws Exception {
        Method method = ResourceAssetController.class.getMethod(name, parameterTypes);
        return method.getAnnotation(PreAuthorize.class).value();
    }
}
