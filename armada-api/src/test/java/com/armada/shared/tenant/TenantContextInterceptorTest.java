package com.armada.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.platform.tenant.service.TenantCodeResolver;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 拦截器单元测试:无头抛 TENANT_MISSING、无效码抛 TENANT_NOT_FOUND、有效码设上下文、afterCompletion 清理。 */
class TenantContextInterceptorTest {

    // 手写假 resolver:demo→1,其余空。
    private final TenantCodeResolver resolver =
            code -> "demo".equals(code) ? Optional.of(1L) : Optional.empty();
    private final TenantContextInterceptor interceptor = new TenantContextInterceptor(resolver);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void setsContext_whenHeaderResolves() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TenantContextInterceptor.TENANT_CODE_HEADER, "demo");

        boolean proceed = interceptor.preHandle(req, new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        assertThat(TenantContext.get()).isEqualTo(1L);
    }

    @Test
    void throwsTenantMissing_whenNoHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TENANT_MISSING.code());
    }

    @Test
    void throwsTenantNotFound_whenUnresolvable() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TenantContextInterceptor.TENANT_CODE_HEADER, "nope");
        assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.TENANT_NOT_FOUND.code());
    }

    @Test
    void afterCompletion_clearsContext() {
        TenantContext.set(9L);
        interceptor.afterCompletion(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);
        assertThat(TenantContext.get()).isNull();
    }
}
