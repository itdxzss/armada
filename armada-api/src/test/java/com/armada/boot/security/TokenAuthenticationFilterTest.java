package com.armada.boot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.admin.service.CurrentIdentityService;
import com.armada.platform.auth.model.AuthSession;
import com.armada.platform.auth.service.SessionService;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.security.DataScopeMode;
import com.armada.shared.tenant.TenantContext;
import jakarta.servlet.ServletException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TokenAuthenticationFilterTest {

    @AfterEach
    void clearContexts() {
        DataScopeContext.clear();
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
            DataScope scope = DataScopeContext.requireCurrent();
            assertThat(scope.mode()).isEqualTo(DataScopeMode.ALL);
            assertThat(scope.actorUserId()).isEqualTo(7L);
        });

        assertThat(DataScopeContext.current()).isEmpty();
        assertThat(TenantContext.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ordinaryUserGetsSelfScopeAndScopeIsClearedWhenChainFails() {
        SessionService sessions = mock(SessionService.class);
        CurrentIdentityService identities = mock(CurrentIdentityService.class);
        SecurityJsonWriter writer = mock(SecurityJsonWriter.class);
        AuthSession session = new AuthSession(9L, 1L, 100L, 100L, 1000L);
        AuthPrincipal principal = new AuthPrincipal(
                9L, 1L, "operator", "运营", "demo", "演示租户",
                List.of("TENANT_OPERATOR"), List.of("tenant:account:view"));
        when(sessions.resolve("ordinary-token")).thenReturn(Optional.of(session));
        when(identities.load(9L, 1L)).thenReturn(Optional.of(principal));
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(sessions, identities, writer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
        request.addHeader("Authorization", "Bearer ordinary-token");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
                    DataScope scope = DataScopeContext.requireCurrent();
                    assertThat(scope.mode()).isEqualTo(DataScopeMode.SELF);
                    assertThat(scope.actorUserId()).isEqualTo(9L);
                    throw new ServletException("downstream failed");
                }))
                .isInstanceOf(ServletException.class)
                .hasMessage("downstream failed");

        assertThat(DataScopeContext.current()).isEmpty();
        assertThat(TenantContext.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void anonymousRequestCannotInheritStaleThreadContexts() throws Exception {
        SessionService sessions = mock(SessionService.class);
        CurrentIdentityService identities = mock(CurrentIdentityService.class);
        SecurityJsonWriter writer = mock(SecurityJsonWriter.class);
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(sessions, identities, writer);
        DataScopeContext.open(DataScope.self(99L));
        TenantContext.set(88L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("stale", null, List.of()));

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/accounts"),
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                    assertThat(DataScopeContext.current()).isEmpty();
                    assertThat(TenantContext.get()).isNull();
                    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                });

        verifyNoInteractions(sessions, identities, writer);
    }

    @Test
    void publicRequestSkipsAuthenticationButStillClearsContexts() throws Exception {
        SessionService sessions = mock(SessionService.class);
        CurrentIdentityService identities = mock(CurrentIdentityService.class);
        SecurityJsonWriter writer = mock(SecurityJsonWriter.class);
        TokenAuthenticationFilter filter = new TokenAuthenticationFilter(sessions, identities, writer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/public/health");
        request.addHeader("Authorization", "Bearer ignored-on-public-route");
        DataScopeContext.open(DataScope.self(99L));
        TenantContext.set(88L);

        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> {
            assertThat(DataScopeContext.current()).isEmpty();
            assertThat(TenantContext.get()).isNull();
        });

        verifyNoInteractions(sessions, identities, writer);
    }
}
