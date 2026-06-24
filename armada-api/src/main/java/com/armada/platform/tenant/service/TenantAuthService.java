package com.armada.platform.tenant.service;

import com.armada.platform.tenant.model.dto.TenantLoginRequest;
import com.armada.platform.tenant.model.vo.TenantLoginVO;
import com.armada.shared.exception.BusinessException;

/** 极简租户登录(先测,无 JWT)。 */
public interface TenantAuthService {

    /** @throws BusinessException 租户码或密码错误(ErrorCode.LOGIN_FAILED) */
    TenantLoginVO login(TenantLoginRequest request);
}
