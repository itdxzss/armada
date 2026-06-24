package com.armada.platform.tenant.service.impl;

import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.platform.tenant.model.dto.TenantLoginRequest;
import com.armada.platform.tenant.model.entity.Tenant;
import com.armada.platform.tenant.model.vo.TenantLoginVO;
import com.armada.platform.tenant.service.TenantAuthService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 极简登录:校验「密码==配置 armada.dev-login.password」且「租户码在 tenant 表且启用」。
 * 任一不满足统一抛 LOGIN_FAILED(不暴露是码错还是密码错)。无 JWT,token 为占位串。
 */
@Service
public class TenantAuthServiceImpl implements TenantAuthService {

    private static final Logger log = LoggerFactory.getLogger(TenantAuthServiceImpl.class);

    private final TenantMapper tenantMapper;
    private final String devPassword;

    public TenantAuthServiceImpl(
            TenantMapper tenantMapper,
            @Value("${armada.dev-login.password:}") String devPassword) {
        this.tenantMapper = tenantMapper;
        this.devPassword = devPassword;
    }

    @Override
    public TenantLoginVO login(TenantLoginRequest request) {
        if (request == null || request.tenantCode() == null || request.password() == null) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        // 配置未设密码(空)则一律拒绝,避免空密码登录。
        if (devPassword == null || devPassword.isBlank() || !devPassword.equals(request.password())) {
            log.warn("login.reject reason=password tenantCode={}", request.tenantCode());
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        Tenant tenant = tenantMapper.selectActiveByCode(request.tenantCode().trim());
        if (tenant == null) {
            log.warn("login.reject reason=tenant_not_found tenantCode={}", request.tenantCode());
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        log.info("login.ok tenantId={} tenantCode={}", tenant.getId(), tenant.getTenantCode());
        return new TenantLoginVO(tenant.getTenantCode(), tenant.getName(), "dev-" + tenant.getTenantCode());
    }
}
