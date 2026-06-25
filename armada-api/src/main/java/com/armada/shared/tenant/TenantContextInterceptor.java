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

    /**
     * 从请求头 {@code X-Tenant-Code} 解析 tenant_id 写入 {@link TenantContext}。
     *
     * <p>缺头抛 {@code TENANT_MISSING}、无效/停用码抛 {@code TENANT_NOT_FOUND}——fail-closed,
     * 把没有合法租户身份的请求挡在业务逻辑之前,避免落到哨兵 tenant_id=-1 上查到跨租户数据。</p>
     *
     * @return {@code true} 放行进入后续处理;失败场景以业务异常中断,不会返回 false
     */
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

    /**
     * 请求结束后清理租户上下文。
     *
     * <p>铁律:{@link TenantContext} 是 ThreadLocal,Tomcat 线程会被复用;不清理会让下一个复用该线程的
     * 请求读到上一个租户的 id,造成跨租户串号,故无论成败都必须清。</p>
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
