package com.armada.platform.tenant.service;

import java.util.Optional;

/**
 * 把租户码解析成 tenant_id。先测阶段来源是 X-Tenant-Code 头;接 JWT 后改为从 token 取,本接口不变。
 */
public interface TenantCodeResolver {

    /** @return 启用租户的 id;租户码为空/未知/停用时返回空。 */
    Optional<Long> resolveTenantId(String tenantCode);
}
