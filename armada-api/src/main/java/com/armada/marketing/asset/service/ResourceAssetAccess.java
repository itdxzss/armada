package com.armada.marketing.asset.service;

import com.armada.marketing.asset.model.enums.ResourceAssetScope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 按请求业务范围检查既有权限，防止养群权限访问超链专用图片接口。 */
@Component("resourceAssetAccess")
public class ResourceAssetAccess {
    /** @param scope 业务范围 @param action view/upload/edit/delete @return 当前身份是否可执行操作 */
    public boolean allowed(ResourceAssetScope scope, String action) {
        if (scope == null || scope == ResourceAssetScope.MARKETING) return false;
        if (scope == ResourceAssetScope.SCRIPT) {
            return switch (action) {
                case "view" -> has("tenant:script_marketing:view");
                case "upload" -> has("tenant:script_marketing:create", "tenant:script_marketing:edit");
                case "edit" -> has("tenant:script_marketing:edit");
                case "delete" -> has("tenant:script_marketing:delete");
                default -> false;
            };
        }
        return switch (action) {
            case "view" -> has("tenant:resource_asset:view", "tenant:hyperlink_template:view",
                    "tenant:hyperlink_template:create", "tenant:hyperlink_template:edit",
                    "tenant:hyperlink_task:view", "tenant:hyperlink_task:create", "tenant:hyperlink_task:edit");
            case "upload" -> has("tenant:resource_asset:upload", "tenant:hyperlink_template:create",
                    "tenant:hyperlink_template:edit", "tenant:hyperlink_task:create", "tenant:hyperlink_task:edit");
            case "edit" -> has("tenant:resource_asset:edit");
            case "delete" -> has("tenant:resource_asset:delete");
            default -> false;
        };
    }

    private boolean has(String... permissions) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return false;
        for (String permission : permissions) {
            if (authentication.getAuthorities().stream().anyMatch(authority -> permission.equals(authority.getAuthority()))) return true;
        }
        return false;
    }
}
