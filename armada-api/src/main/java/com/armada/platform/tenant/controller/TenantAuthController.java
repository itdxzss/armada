package com.armada.platform.tenant.controller;

import com.armada.platform.tenant.model.dto.TenantLoginRequest;
import com.armada.platform.tenant.model.vo.TenantLoginVO;
import com.armada.platform.tenant.service.TenantAuthService;
import com.armada.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户登录公开入口(免租户拦截:路径在 /api/public/** 排除名单内)。 */
@RestController
@RequestMapping("/api/public/auth")
public class TenantAuthController {

    private final TenantAuthService authService;

    public TenantAuthController(TenantAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<TenantLoginVO> login(@RequestBody TenantLoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }
}
