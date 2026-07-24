package com.armada.boot.security;

import com.armada.admin.service.CurrentIdentityService;
import com.armada.platform.auth.exception.AuthInfrastructureException;
import com.armada.platform.auth.model.AuthSession;
import com.armada.platform.auth.service.SessionService;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 从 Redis Bearer Token 恢复实时 RBAC 权限并建立租户上下文。 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final SessionService sessionService;
    private final CurrentIdentityService identityService;
    private final SecurityJsonWriter responseWriter;

    public TokenAuthenticationFilter(
            SessionService sessionService,
            CurrentIdentityService identityService,
            SecurityJsonWriter responseWriter) {
        this.sessionService = sessionService;
        this.identityService = identityService;
        this.responseWriter = responseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/public/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            Optional<AuthSession> session = sessionService.resolve(token);
            if (session.isEmpty()) {
                filterChain.doFilter(request, response);
                return;
            }
            authenticate(session.get());
            filterChain.doFilter(request, response);
        } catch (AuthInfrastructureException ex) {
            log.error("认证基础设施不可用: method={}, path={}", request.getMethod(), request.getRequestURI(), ex);
            responseWriter.write(response, HttpStatus.SERVICE_UNAVAILABLE.value(),
                    ErrorCode.AUTH_SERVICE_UNAVAILABLE);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(AuthSession session) {
        TenantContext.set(session.tenantId());
        Optional<AuthPrincipal> principal = identityService.load(session.userId(), session.tenantId());
        if (principal.isEmpty()) {
            sessionService.invalidateUser(session.userId());
            TenantContext.clear();
            return;
        }
        AuthPrincipal identity = principal.get();
        ArrayList<SimpleGrantedAuthority> authorities = new ArrayList<>();
        identity.roleCodes().forEach(code -> authorities.add(new SimpleGrantedAuthority("ROLE_" + code)));
        identity.permissions().forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(identity, null, authorities));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return "";
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
