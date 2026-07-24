package com.armada.admin.controller;

import com.armada.admin.model.dto.UserLoginDTO;
import com.armada.admin.model.vo.CurrentAuthVO;
import com.armada.admin.model.vo.UserLoginVO;
import com.armada.admin.service.AuthenticationService;
import com.armada.platform.auth.model.CaptchaChallenge;
import com.armada.platform.auth.service.CaptchaService;
import com.armada.platform.auth.service.SessionService;
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

    public AuthenticationController(
            CaptchaService captchaService,
            AuthenticationService authenticationService,
            SessionService sessionService) {
        this.captchaService = captchaService;
        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
    }

    /** 生成一次性图片验证码，禁止浏览器和代理缓存。 */
    @GetMapping("/api/public/auth/captcha")
    public ApiResponse<CaptchaChallenge> captcha(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(captchaService.create());
    }

    /** 校验用户名、密码和验证码并创建 Redis 单会话。 */
    @PostMapping("/api/public/auth/login")
    public ApiResponse<UserLoginVO> login(@RequestBody UserLoginDTO request) {
        return ApiResponse.ok(authenticationService.login(request));
    }

    /** 返回服务端认证上下文中的当前用户和租户。 */
    @GetMapping("/api/auth/me")
    public ApiResponse<CurrentAuthVO> current(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(authenticationService.current(principal));
    }

    /** 删除当前 Bearer Token 对应的服务端会话。 */
    @PostMapping("/api/auth/logout")
    public ApiResponse<Void> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        sessionService.logout(rawToken(authorization));
        return ApiResponse.ok();
    }

    private static String rawToken(String authorization) {
        return authorization != null && authorization.startsWith(BEARER_PREFIX)
                ? authorization.substring(BEARER_PREFIX.length()).trim() : "";
    }
}
