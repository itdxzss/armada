package com.armada.platform.tenant.service.impl;

import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.platform.tenant.model.entity.Tenant;
import com.armada.platform.tenant.service.TenantCodeResolver;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 查 tenant 表解析租户码 → tenant_id。 */
@Service
public class TenantCodeResolverImpl implements TenantCodeResolver {

    private final TenantMapper tenantMapper;

    public TenantCodeResolverImpl(TenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    @Override
    public Optional<Long> resolveTenantId(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return Optional.empty();
        }
        Tenant tenant = tenantMapper.selectActiveByCode(tenantCode.trim());
        return tenant == null ? Optional.empty() : Optional.of(tenant.getId());
    }
}
