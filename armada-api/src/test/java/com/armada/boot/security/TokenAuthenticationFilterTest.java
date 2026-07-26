package com.armada.boot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.admin.service.CurrentIdentityService;
import com.armada.platform.auth.model.AuthSession;
import com.armada.platform.auth.service.SessionService;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class TokenAuthenticationFilterTest {

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedSessionUsesServerTenantAndIgnoresForgedTenantHeader() throws Exception {
        SessionService sessions = mock(SessionService.class);
        CurrentIdentityService identities = mock(CurrentIdentityService.class);
        SecurityJsonWriter writer = mock(SecurityJsonWriter.class);
        AuthSession session = new AuthSession(7L, 1L, 100L, 100L, 1000L);
        AuthPrincipal principal = new AuthPrincipal(
                7L, 1L, "admin", "管理员", "demo", "演示租户",
                List.of("TENANT_ADMIN"), List.of("tenant:system-user:view"));
        when(sessions.resolve("valid-token")).thenReturn(Optional.of(session));
        when(identities.load(7L, 1L)).thenReturn(Optional.of(principal));
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(sessions, identities, writer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.addHeader("Authorization", "Bearer valid-token");
        request.addHeader("X-Tenant-Code", "demo2");

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            assertThat(TenantContext.get()).isEqualTo(1L);
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isEqualTo(principal);
            assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                    .extracting(Object::toString)
                    .contains("ROLE_TENANT_ADMIN", "tenant:system-user:view");
        });

        assertThat(TenantContext.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
