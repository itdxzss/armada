package com.armada.platform.tenant.service;

import com.armada.platform.tenant.model.dto.TenantLoginRequest;
import com.armada.platform.tenant.model.vo.TenantLoginVO;
import com.armada.shared.exception.BusinessException;

/** 极简租户登录(先测,无 JWT)。 */
public interface TenantAuthService {

    /**
     * 校验租户码与密码,成功后签发登录结果。
     *
     * <p>先测阶段:密码与配置 {@code armada.dev-login.password} 比对,租户码须在 {@code tenant} 表且启用;
     * 不签 JWT,返回的 token 仅为占位串。</p>
     *
     * @param request 登录入参(租户码 + 密码)
     * @return 登录结果(租户码、租户名、占位 token)
     * @throws BusinessException 租户码或密码错误,统一抛 {@link com.armada.shared.exception.ErrorCode#LOGIN_FAILED}
     */
    TenantLoginVO login(TenantLoginRequest request);
}
