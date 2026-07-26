package com.armada.admin.controller;

import com.armada.admin.model.vo.MenuRouteVO;
import com.armada.admin.service.MenuManagementService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户动态菜单入口。 */
@RestController
@RequestMapping("/api/tenant/me")
public class CurrentMenuController {

    private final MenuManagementService menuService;

    public CurrentMenuController(MenuManagementService menuService) {
        this.menuService = menuService;
    }

    /** 按当前有效角色返回动态路由树和按钮权限。 */
    @GetMapping("/menus")
    public ApiResponse<List<MenuRouteVO>> menus(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(menuService.findEffectiveRoutesForUser(principal.userId()));
    }
}
