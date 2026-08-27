package com.armada.boot.security;

import com.armada.admin.service.CurrentIdentityService;
import com.armada.platform.auth.exception.AuthInfrastructureException;
import com.armada.platform.auth.model.AuthSession;
import com.armada.platform.auth.service.SessionService;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
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
    private static final String PUBLIC_API_PREFIX = "/api/public/";

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
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        clearRequestContexts();
        try {
            if (request.getRequestURI().startsWith(PUBLIC_API_PREFIX)) {
                filterChain.doFilter(request, response);
                return;
            }
            String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (token.isEmpty()) {
                filterChain.doFilter(request, response);
                return;
            }
            Optional<AuthSession> session = sessionService.resolve(token);
            if (session.isEmpty()) {
                log.debug("auth.token.reject reason=missing_or_expired method={} path={}",
                        request.getMethod(), request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }
            Optional<DataScope> dataScope = authenticate(session.get(), request);
            if (dataScope.isPresent()) {
                try (DataScopeContext.Scope ignored = DataScopeContext.open(dataScope.get())) {
                    filterChain.doFilter(request, response);
                }
            } else {
                filterChain.doFilter(request, response);
            }
        } catch (AuthInfrastructureException ex) {
            log.error("认证基础设施不可用: method={}, path={}", request.getMethod(), request.getRequestURI(), ex);
            responseWriter.write(response, HttpStatus.SERVICE_UNAVAILABLE.value(),
                    ErrorCode.AUTH_SERVICE_UNAVAILABLE);
        } finally {
            clearRequestContexts();
        }
    }

    /** 请求开始和结束都清理线程上下文，匿名/公共请求也不能继承线程池残留身份。 */
    private static void clearRequestContexts() {
        DataScopeContext.clear();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private Optional<DataScope> authenticate(AuthSession session, HttpServletRequest request) {
        TenantContext.set(session.tenantId());
        Optional<AuthPrincipal> principal = identityService.load(session.userId(), session.tenantId());
        if (principal.isEmpty()) {
            log.warn("auth.token.reject reason=identity_invalid userId={} tenantId={} method={} path={}",
                    session.userId(), session.tenantId(), request.getMethod(), request.getRequestURI());
            sessionService.invalidateUser(session.userId());
            TenantContext.clear();
            return Optional.empty();
        }
        AuthPrincipal identity = principal.get();
        ArrayList<SimpleGrantedAuthority> authorities = new ArrayList<>();
        identity.roleCodes().forEach(code -> authorities.add(new SimpleGrantedAuthority("ROLE_" + code)));
        identity.permissions().forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(identity, null, authorities));
        log.debug("auth.token.accept userId={} tenantId={} authorityCount={} method={} path={}",
                identity.userId(), identity.tenantId(), authorities.size(),
                request.getMethod(), request.getRequestURI());
        return Optional.of(DataScope.fromPrincipal(identity));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return "";
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
