package com.armada.boot.security;

import com.armada.account.controller.DeviceImportController;
import com.armada.account.model.dto.DeviceImportDefaults;
import com.armada.boot.web.DeviceImportRequestConverter;
import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只注册到设备安全链的静态令牌过滤器；不注册 Servlet 全局过滤器，不使用管理员身份。 */
public final class DeviceIngestAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DeviceIngestAuthenticationFilter.class);
    private static final String TOKEN_HEADER = "X-Ingest-Token";
    private final DeviceIngestTokens tokens;
    private final TenantMapper tenants;
    private final ObjectMapper json = new ObjectMapper();

    /** 注入进程内配置和已有租户注册表查询。 */
    public DeviceIngestAuthenticationFilter(DeviceIngestTokens tokens, TenantMapper tenants) {
        this.tokens = tokens;
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        try {
            if (!validRoute(request, response)) {
                return;
            }
            List<String> headers = Collections.list(request.getHeaders(TOKEN_HEADER));
            Optional<DeviceImportDefaults> defaults = headers.size() == 1
                    ? tokens.resolve(headers.get(0)) : Optional.empty();
            if (defaults.isEmpty()) {
                reject(response, HttpStatus.UNAUTHORIZED, "导入令牌无效");
                return;
            }
            if (tenants.selectActiveById(defaults.get().tenantId()) == null) {
                reject(response, HttpStatus.SERVICE_UNAVAILABLE, "导入配置不可用，请联系管理员");
                return;
            }
            if (!validContent(request, response)) {
                return;
            }
            if (!acceptsJson(request)) {
                reject(response, HttpStatus.NOT_ACCEPTABLE, "响应仅支持 application/json");
                return;
            }
            TenantContext.set(defaults.get().tenantId());
            request.setAttribute(DeviceImportController.DEFAULTS_ATTRIBUTE, defaults.get());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            chain.doFilter(request, response);
        } catch (DataAccessException ex) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "导入服务暂不可用，请稍后重试");
        } catch (Exception ex) {
            reject(response, HttpStatus.INTERNAL_SERVER_ERROR, "导入服务暂不可用，请稍后重试");
        } finally {
            request.removeAttribute(DeviceImportController.DEFAULTS_ATTRIBUTE);
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean validRoute(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!DeviceImportController.PATHS.contains(request.getRequestURI()) || request.getQueryString() != null) {
            reject(response, HttpStatus.BAD_REQUEST, "请求路径不正确");
            return false;
        }
        HttpMethod method = DeviceImportController.GROUPS_PATH.equals(request.getRequestURI())
                ? HttpMethod.GET : HttpMethod.POST;
        String allowed = method.name() + ", OPTIONS";
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            response.setHeader(HttpHeaders.ALLOW, allowed);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setStatus(HttpStatus.NO_CONTENT.value());
            return false;
        }
        if (!method.matches(request.getMethod())) {
            response.setHeader(HttpHeaders.ALLOW, allowed);
            reject(response, HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持");
            return false;
        }
        return true;
    }

    private boolean validContent(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (HttpMethod.GET.matches(request.getMethod())) {
            // 分组查询不接收凭据或筛选参数，未知长度的请求体同样拒绝。
            if (request.getInputStream().read() != -1) {
                reject(response, HttpStatus.BAD_REQUEST, "分组查询不能包含请求体");
                return false;
            }
            return true;
        }
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType());
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())) {
                reject(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求必须使用 application/json");
                return false;
            }
        } catch (IllegalArgumentException ex) {
            reject(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求必须使用 application/json");
            return false;
        }
        if (request.getContentLengthLong() > DeviceImportRequestConverter.MAX_BODY_BYTES) {
            reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "请求内容过大");
            return false;
        }
        return true;
    }

    private boolean acceptsJson(HttpServletRequest request) {
        String accept = String.join(",", Collections.list(request.getHeaders(HttpHeaders.ACCEPT)));
        if (accept.isBlank()) {
            return true;
        }
        try {
            return MediaType.parseMediaTypes(accept).stream()
                    .anyMatch(type -> type.getQualityValue() > 0 && type.isCompatibleWith(MediaType.APPLICATION_JSON));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        log.warn("device.import.reject code={}", status.value());
        if (!response.isCommitted()) {
            response.resetBuffer();
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            json.writeValue(response.getOutputStream(), Map.of("message", message));
        }
    }
}
