package com.armada.admin.service.impl;

import com.armada.admin.model.dto.UserLoginDTO;
import com.armada.admin.model.entity.SysUser;
import com.armada.admin.model.enums.SystemStatus;
import com.armada.admin.model.vo.AuthTenantVO;
import com.armada.admin.model.vo.AuthUserVO;
import com.armada.admin.model.vo.CurrentAuthVO;
import com.armada.admin.model.vo.UserLoginVO;
import com.armada.admin.service.AuthenticationService;
import com.armada.admin.service.CurrentIdentityService;
import com.armada.platform.auth.model.CreatedSession;
import com.armada.platform.auth.service.CaptchaService;
import com.armada.platform.auth.service.SessionService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 默认租户阶段的用户名、密码和图片验证码登录实现。
 *
 * <p>账号不存在或密码错误时统一返回相同错误，避免暴露用户是否存在；密码正确但账号已禁用时给出明确提示。
 * 服务端日志只记录脱敏用户名和失败原因，不记录密码、验证码或 Token。</p>
 */
@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationServiceImpl.class);
    private static final String TOKEN_TYPE = "Bearer";

    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captchaService;
    private final SessionService sessionService;
    private final CurrentIdentityService identityService;
    private final String dummyPasswordHash;

    public AuthenticationServiceImpl(
            PasswordEncoder passwordEncoder,
            CaptchaService captchaService,
            SessionService sessionService,
            CurrentIdentityService identityService) {
        this.passwordEncoder = passwordEncoder;
        this.captchaService = captchaService;
        this.sessionService = sessionService;
        this.identityService = identityService;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public UserLoginVO login(UserLoginDTO request) {
        if (request == null || !captchaService.consume(request.captchaId(), request.captchaCode())) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID);
        }
        String username = normalizeUsername(request.username());
        String password = request.password() == null ? "" : request.password();
        Optional<SysUser> candidate = identityService.findLoginUser(username);
        SysUser user = candidate.orElse(null);
        String storedHash = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password, storedHash);
        if (user == null) {
            log.warn("login.reject reason=user_not_found username={}", mask(username));
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (!passwordMatches) {
            log.warn("login.reject reason=password_mismatch username={}", mask(username));
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() != SystemStatus.ENABLED.code()) {
            log.warn("login.reject reason=user_disabled userId={} tenantId={}",
                    user.getId(), user.getTenantId());
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        long tenantId = user.getTenantId();

        AuthPrincipal principal;
        TenantContext.set(tenantId);
        try {
            principal = identityService.load(user.getId(), tenantId).orElseThrow(() -> {
                log.warn("login.reject reason=identity_invalid userId={} tenantId={}", user.getId(), tenantId);
                return new BusinessException(ErrorCode.LOGIN_FAILED);
            });
        } finally {
            TenantContext.clear();
        }
        CreatedSession session = sessionService.create(user.getId(), tenantId);
        log.info("login.ok userId={} tenantId={}", user.getId(), tenantId);
        CurrentAuthVO current = current(principal);
        return new UserLoginVO(session.token(), TOKEN_TYPE,
                session.idleTimeoutSeconds(), session.absoluteExpiresAt(),
                current.user(), current.tenant());
    }

    @Override
    public CurrentAuthVO current(AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
        return new CurrentAuthVO(
                new AuthUserVO(principal.userId(), principal.username(), principal.nickname(),
                        principal.roleCodes(), principal.permissions()),
                new AuthTenantVO(principal.tenantId(), principal.tenantCode(), principal.tenantName()));
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.trim().isEmpty() || username.trim().length() > 64) {
            return "";
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String mask(String username) {
        if (username == null || username.length() < 3) {
            return "***";
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }
}
