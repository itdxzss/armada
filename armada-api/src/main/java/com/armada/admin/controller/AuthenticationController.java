package com.armada.admin.controller;

import com.armada.admin.model.dto.PasswordChangeDTO;
import com.armada.admin.model.dto.UserLoginDTO;
import com.armada.admin.model.vo.CurrentAuthVO;
import com.armada.admin.model.vo.UserLoginVO;
import com.armada.admin.service.AuthenticationService;
import com.armada.admin.service.UserManagementService;
import com.armada.platform.auth.model.CaptchaChallenge;
import com.armada.platform.auth.service.CaptchaService;
import com.armada.platform.auth.service.SessionService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端图片验证码、真实用户登录、当前用户和退出接口。 */
@RestController
public class AuthenticationController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final CaptchaService captchaService;
    private final AuthenticationService authenticationService;
    private final SessionService sessionService;
    private final UserManagementService userManagementService;

    public AuthenticationController(
            CaptchaService captchaService,
            AuthenticationService authenticationService,
            SessionService sessionService,
            UserManagementService userManagementService) {
        this.captchaService = captchaService;
        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
        this.userManagementService = userManagementService;
    }

    /**
     * 生成一次性图片验证码，并禁止浏览器和代理缓存验证码响应。
     *
     * @param response HTTP 响应，用于写入禁用缓存的响应头
     * @return 验证码标识、Base64 图片和有效秒数
     */
    @GetMapping("/api/public/auth/captcha")
    public ApiResponse<CaptchaChallenge> captcha(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(captchaService.create());
    }

    /**
     * 校验用户名和密码，并创建 Redis 单用户单会话；图片验证码校验当前临时关闭。
     *
     * @param request 登录账号和密码，暂时保留验证码字段供后续恢复
     * @return Token、过期信息和当前身份
     */
    @PostMapping("/api/public/auth/login")
    public ApiResponse<UserLoginVO> login(@RequestBody UserLoginDTO request) {
        return ApiResponse.ok(authenticationService.login(request));
    }

    /**
     * 返回服务端认证上下文中的当前用户、租户、角色和权限。
     *
     * @param principal Token 过滤器建立的可信身份
     * @return 当前登录身份
     */
    @GetMapping("/api/auth/me")
    public ApiResponse<CurrentAuthVO> current(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(authenticationService.current(principal));
    }

    /**
     * 删除当前 Bearer Token 对应的服务端会话。
     *
     * @param authorization 当前请求的 Authorization 头
     * @return 空成功响应
     */
    @PostMapping("/api/auth/logout")
    public ApiResponse<Void> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        sessionService.logout(rawToken(authorization));
        return ApiResponse.ok();
    }

    /**
     * 校验当前密码并修改当前登录用户密码，成功后使该用户已有会话失效。
     *
     * @param principal Token 过滤器建立的可信身份
     * @param request 当前密码和新密码
     * @return 空成功响应
     */
    @PostMapping("/api/auth/change-password")
    public ApiResponse<Void> changePassword(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody PasswordChangeDTO request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
        userManagementService.changeOwnPassword(
                principal.userId(),
                request == null ? null : request.currentPassword(),
                request == null ? null : request.newPassword());
        return ApiResponse.ok();
    }

    private static String rawToken(String authorization) {
        return authorization != null && authorization.startsWith(BEARER_PREFIX)
                ? authorization.substring(BEARER_PREFIX.length()).trim() : "";
    }
}
