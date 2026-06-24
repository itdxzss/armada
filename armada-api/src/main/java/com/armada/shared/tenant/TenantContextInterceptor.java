package com.armada.shared.tenant;

import com.armada.platform.tenant.service.TenantCodeResolver;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户上下文拦截器:从请求头 {@code X-Tenant-Code} 解析 tenant_id 写入 {@link TenantContext},
 * 供 MyBatis 租户行隔离拦截器读取。无头/无效码 fail-closed 抛业务异常(经 GlobalExceptionHandler 转 code≠0)。
 *
 * <p>先测阶段:租户码来自前端登录后存的头,无 JWT、可被伪造,无真安全;接 JWT 后改为从 token 取,本类结构不变。</p>
 * <p>铁律:{@code afterCompletion} 必清 ThreadLocal,防线程池复用串号。</p>
 */
public class TenantContextInterceptor implements HandlerInterceptor {

    public static final String TENANT_CODE_HEADER = "X-Tenant-Code";

    private static final Logger log = LoggerFactory.getLogger(TenantContextInterceptor.class);

    private final TenantCodeResolver tenantCodeResolver;

    public TenantContextInterceptor(TenantCodeResolver tenantCodeResolver) {
        this.tenantCodeResolver = tenantCodeResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String code = request.getHeader(TENANT_CODE_HEADER);
        if (code == null || code.isBlank()) {
            log.warn("tenant.reject reason=missing method={} path={}",
                    request.getMethod(), request.getRequestURI());
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        Long tenantId = tenantCodeResolver.resolveTenantId(code)
                .orElseThrow(() -> {
                    log.warn("tenant.reject reason=not_found code={} method={} path={}",
                            code, request.getMethod(), request.getRequestURI());
                    return new BusinessException(ErrorCode.TENANT_NOT_FOUND);
                });
        TenantContext.set(tenantId);
        log.debug("tenant.set tenantId={} code={} path={}", tenantId, code, request.getRequestURI());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
