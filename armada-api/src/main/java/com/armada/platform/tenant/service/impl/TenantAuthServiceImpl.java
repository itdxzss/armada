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

    /** 先测阶段占位 token 的前缀,拼在租户码前(非真鉴权凭据);接 JWT 后随登录改造整体删除。 */
    private static final String DEV_TOKEN_PREFIX = "dev-";

    private final TenantMapper tenantMapper;
    private final String devPassword;

    public TenantAuthServiceImpl(
            TenantMapper tenantMapper,
            @Value("${armada.dev-login.password:}") String devPassword) {
        this.tenantMapper = tenantMapper;
        this.devPassword = devPassword;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:参数缺失 / 密码不符 / 租户不存在或停用,一律抛同一个 {@code LOGIN_FAILED},
     * 不区分失败原因——防止有人借不同报错枚举出有效租户码(fail-closed);配置未设密码(空)时一律拒绝,
     * 杜绝空密码登录。日志只打 tenantCode、绝不打密码(脱敏)。</p>
     */
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
        return new TenantLoginVO(
                tenant.getTenantCode(), tenant.getName(), DEV_TOKEN_PREFIX + tenant.getTenantCode());
    }
}
