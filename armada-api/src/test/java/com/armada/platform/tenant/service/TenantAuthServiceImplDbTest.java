package com.armada.platform.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.platform.tenant.model.dto.TenantLoginRequest;
import com.armada.platform.tenant.model.vo.TenantLoginVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 登录服务真库测试:密码对+租户在→成功;密码错/租户不存在→统一 LOGIN_FAILED。 */
@TestPropertySource(properties = "armada.dev-login.password=armada123")
class TenantAuthServiceImplDbTest extends DbTestBase {

    @Autowired private TenantAuthService authService;

    @Test
    void login_ok_returnsTenantAndPlaceholderToken() {
        TenantLoginVO vo = authService.login(new TenantLoginRequest("demo", "armada123"));
        assertThat(vo.tenantCode()).isEqualTo("demo");
        assertThat(vo.tenantName()).isEqualTo("演示租户A");
        assertThat(vo.token()).isNotBlank();
    }

    @Test
    void login_wrongPassword_fails() {
        assertThatThrownBy(() -> authService.login(new TenantLoginRequest("demo", "bad")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED.code());
    }

    @Test
    void login_unknownTenant_fails() {
        assertThatThrownBy(() -> authService.login(new TenantLoginRequest("nope", "armada123")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED.code());
    }
}
