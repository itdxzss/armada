package com.armada.boot.security;

import com.armada.account.controller.DeviceRegistrationController;
import com.armada.account.model.dto.DeviceRegistrationResultDTO;
import com.armada.account.model.dto.DeviceRegistrationStartDTO;
import com.armada.account.model.dto.DeviceRegistrationIdentity;
import com.armada.account.model.dto.DeviceRegistrationBeginDTO;
import com.armada.account.model.enums.DeviceRegistrationFailureKind;
import com.armada.account.service.DeviceRegistrationPermitService;
import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 注册独立静态令牌链；上下文只在当前请求存在，不授予管理员权限。 */
public final class DeviceRegistrationAuthenticationFilter extends OncePerRequestFilter {
    private static final int MAX_BODY = 2048;
    private final DeviceRegistrationTokens tokens;
    private final TenantMapper tenants;
    private final DeviceRegistrationPermitService permits;
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 不注册为全局 Servlet 过滤器，仅由专用安全链创建。 */
    public DeviceRegistrationAuthenticationFilter(DeviceRegistrationTokens tokens, TenantMapper tenants,
            DeviceRegistrationPermitService permits) {
        this.tokens = tokens; this.tenants = tenants; this.permits = permits;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException {
        TenantContext.clear(); SecurityContextHolder.clearContext();
        response.setHeader("Cache-Control", "no-store");
        try {
            if (!DeviceRegistrationController.PATHS.contains(request.getRequestURI()) || request.getQueryString() != null) {
                reject(response, 400); return;
            }
            if (!"POST".equals(request.getMethod())) { response.setHeader("Allow", "POST"); reject(response, 405); return; }
            var token = Collections.list(request.getHeaders("X-Registration-Token"));
            var device = Collections.list(request.getHeaders("X-Device-ID"));
            Optional<DeviceRegistrationIdentity> identity = token.size() <= 1 && device.size() == 1
                    ? tokens.resolve(token.isEmpty() ? null : token.get(0), device.get(0)) : Optional.empty();
            if (identity.isEmpty()) { reject(response, 401); return; }
            if (tenants.selectActiveById(identity.orElseThrow().tenantId()) == null) { reject(response, 503); return; }
            TenantContext.set(identity.orElseThrow().tenantId());
            if (request.getRequestURI().equals(DeviceRegistrationController.PREFIX + "begin")) {
                if (!parseBody(request, response, "")) { return; }
                request.setAttribute(DeviceRegistrationController.IDENTITY, identity.orElseThrow());
            } else {
                var permit = permits.current(identity.orElseThrow());
                if (!parseBody(request, response, permit.requestId())) { return; }
                request.setAttribute(DeviceRegistrationController.PERMIT, permit);
            }
            chain.doFilter(request, response);
        } catch (com.armada.shared.exception.BusinessException exception) {
            int code = exception.getCode();
            reject(response, code == com.armada.shared.exception.ErrorCode.NOT_FOUND.code() ? 404 : 409);
        } catch (Exception exception) {
            // 固定响应不包含令牌、号码、验证码及解析异常原文。
            reject(response, 503);
        } finally {
            request.removeAttribute(DeviceRegistrationController.PERMIT);
            request.removeAttribute(DeviceRegistrationController.RESULT);
            request.removeAttribute(DeviceRegistrationController.START);
            request.removeAttribute(DeviceRegistrationController.IDENTITY);
            request.removeAttribute(DeviceRegistrationController.BEGIN);
            TenantContext.clear(); SecurityContextHolder.clearContext();
        }
    }
    private boolean parseBody(HttpServletRequest request, HttpServletResponse response, String requestId) throws IOException {
        try {
            var type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType());
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())) {
                reject(response, 415); return false;
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) { reject(response, 413); return false; }
            var body = json.readTree(bytes);
            if (body == null || !body.isObject()) { reject(response, 400); return false; }
            if (request.getRequestURI().endsWith("/begin")) {
                if (body.size() != 3 || !body.path("requestId").isTextual() || !body.path("countryId").isTextual()
                        || !(body.path("unitPrice").isTextual() || body.path("unitPrice").isNumber())) {
                    reject(response, 400); return false;
                }
                request.setAttribute(DeviceRegistrationController.BEGIN, json.treeToValue(body, DeviceRegistrationBeginDTO.class));
            } else if (request.getRequestURI().endsWith("/result")) {
                Set<String> fields = "FAILED".equals(body.path("outcome").textValue())
                        ? Set.of("requestId", "phoneNumber", "outcome", "failureKind", "failureCode", "failureDetail")
                        : Set.of("requestId", "phoneNumber", "outcome");
                if (body.size() != fields.size()) { reject(response, 400); return false; }
                var names = body.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    if (!fields.contains(name) || !body.path(name).isTextual()) { reject(response, 400); return false; }
                }
                if ("FAILED".equals(body.path("outcome").textValue())) {
                    // 禁止 Jackson 把数字字符串按 enum ordinal 强制转换。
                    DeviceRegistrationFailureKind.valueOf(body.path("failureKind").textValue());
                }
                request.setAttribute(DeviceRegistrationController.RESULT, json.treeToValue(body, DeviceRegistrationResultDTO.class));
            } else if (request.getRequestURI().endsWith("/start")) {
                // 许可更新后，旧客户端的空请求或旧任务重试不能采购新订单。
                if (body.size() != 2 || !body.path("requestId").isTextual() || !body.path("providerId").isTextual()) {
                    reject(response, 400); return false;
                }
                if (!requestId.equals(body.get("requestId").textValue())) { reject(response, 409); return false; }
                String provider = body.get("providerId").textValue();
                if (!(provider.isEmpty() || provider.matches("[1-9][0-9]{0,31}"))) { reject(response, 400); return false; }
                request.setAttribute(DeviceRegistrationController.START, json.treeToValue(body, DeviceRegistrationStartDTO.class));
            } else if (!body.isEmpty()) { reject(response, 400); return false; }
            return true;
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException exception) {
            reject(response, 400); return false;
        }
    }
    private void reject(HttpServletResponse response, int status) throws IOException {
        if (response.isCommitted()) { return; }
        response.resetBuffer(); response.setStatus(status); response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8"); response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), Map.of("code", status, "message", "手机注册请求未受理"));
    }
}
