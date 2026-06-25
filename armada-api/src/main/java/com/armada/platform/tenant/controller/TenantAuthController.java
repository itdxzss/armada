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

    /**
     * 租户登录:校验租户码 + 密码,成功返回租户信息与占位 token。
     *
     * <p>失败由 Service 抛 {@code LOGIN_FAILED},经全局异常处理转成 {@code code≠0} 的 {@link ApiResponse}。
     * 本端点在 {@code /api/public/**} 排除名单内,不过租户拦截器(登录时还没有租户上下文)。</p>
     *
     * @param request 登录入参(租户码 + 密码)
     * @return 登录结果(租户码、租户名、占位 token)
     */
    @PostMapping("/login")
    public ApiResponse<TenantLoginVO> login(@RequestBody TenantLoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }
}
