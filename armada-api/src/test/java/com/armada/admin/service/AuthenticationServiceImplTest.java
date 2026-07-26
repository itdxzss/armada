package com.armada.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.admin.model.dto.UserLoginDTO;
import com.armada.admin.model.entity.SysUser;
import com.armada.admin.service.impl.AuthenticationServiceImpl;
import com.armada.platform.auth.model.CreatedSession;
import com.armada.platform.auth.service.CaptchaService;
import com.armada.platform.auth.service.SessionService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceImplTest {

    @Mock private CaptchaService captchaService;
    @Mock private SessionService sessionService;
    @Mock private CurrentIdentityService identityService;

    private PasswordEncoder passwordEncoder;
    private AuthenticationServiceImpl service;

    @BeforeEach
    void setUp() {
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        service = new AuthenticationServiceImpl(
                passwordEncoder, captchaService, sessionService, identityService);
    }

    @Test
    void loginConsumesCaptchaAndReturnsRealIdentityAndToken() {
        UserLoginDTO request = new UserLoginDTO(" Admin ", "armada123", "captcha-1", "ABCD");
        SysUser user = enabledUser(passwordEncoder.encode("armada123"));
        AuthPrincipal principal = new AuthPrincipal(
                7L, 1L, "admin", "管理员", "demo", "演示租户",
                List.of("TENANT_ADMIN"), List.of("tenant:system-user:view"));
        when(captchaService.consume("captcha-1", "ABCD")).thenReturn(true);
        when(identityService.findLoginUser("admin")).thenReturn(Optional.of(user));
        when(identityService.load(7L, 1L)).thenReturn(Optional.of(principal));
        when(sessionService.create(7L, 1L))
                .thenReturn(new CreatedSession("real-token", 1800L, 123456L));

        var result = service.login(request);

        assertThat(result.token()).isEqualTo("real-token");
        assertThat(result.user().roles()).containsExactly("TENANT_ADMIN");
        assertThat(result.user().permissions()).containsExactly("tenant:system-user:view");
        assertThat(result.tenant().code()).isEqualTo("demo");
    }

    @Test
    void loginRejectsCaptchaBeforePasswordOrDatabaseLookup() {
        UserLoginDTO request = new UserLoginDTO("admin", "armada123", "expired", "ABCD");
        when(captchaService.consume("expired", "ABCD")).thenReturn(false);

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("验证码错误或已过期");
        verify(identityService, never()).findLoginUser("admin");
    }

    @Test
    void loginUsesGenericErrorForWrongPassword() {
        UserLoginDTO request = new UserLoginDTO("admin", "wrong-password", "captcha-2", "EFGH");
        SysUser user = enabledUser(passwordEncoder.encode("armada123"));
        when(captchaService.consume("captcha-2", "EFGH")).thenReturn(true);
        when(identityService.findLoginUser("admin")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号或密码错误");
        verify(sessionService, never()).create(7L, 1L);
    }

    @Test
    void loginExplainsDisabledAccountAfterPasswordMatches() {
        UserLoginDTO request = new UserLoginDTO("admin", "armada123", "captcha-3", "IJKL");
        SysUser user = enabledUser(passwordEncoder.encode("armada123"));
        user.setStatus(0);
        when(captchaService.consume("captcha-3", "IJKL")).thenReturn(true);
        when(identityService.findLoginUser("admin")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号已禁用，请联系管理员");
        verify(sessionService, never()).create(7L, 1L);
    }

    private static SysUser enabledUser(String passwordHash) {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setTenantId(1L);
        user.setUsername("admin");
        user.setNickname("管理员");
        user.setPasswordHash(passwordHash);
        user.setStatus(1);
        return user;
    }

}
