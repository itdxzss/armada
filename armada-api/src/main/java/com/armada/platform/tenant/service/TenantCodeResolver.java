package com.armada.platform.tenant.service;

import java.util.Optional;

/**
 * 把租户码解析成 tenant_id。先测阶段来源是 X-Tenant-Code 头;接 JWT 后改为从 token 取,本接口不变。
 */
public interface TenantCodeResolver {

    /**
     * 把租户码解析成 tenant_id。
     *
     * @param tenantCode 租户码,来自请求头 {@code X-Tenant-Code}(先测)或 token(接 JWT 后);可能带首尾空白,由实现 trim
     * @return 启用租户的 id;租户码为空白、未知或租户已停用时返回 {@link Optional#empty()}
     */
    Optional<Long> resolveTenantId(String tenantCode);
}
