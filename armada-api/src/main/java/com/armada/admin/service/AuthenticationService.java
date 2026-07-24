package com.armada.admin.service;

import com.armada.admin.model.dto.UserLoginDTO;
import com.armada.admin.model.vo.CurrentAuthVO;
import com.armada.admin.model.vo.UserLoginVO;
import com.armada.shared.security.AuthPrincipal;

/** 管理端真实用户登录与当前身份服务。 */
public interface AuthenticationService {

    /** 校验验证码和密码，创建单会话并返回当前用户。 */
    UserLoginVO login(UserLoginDTO request);

    /** 把可信 Principal 转为当前用户响应。 */
    CurrentAuthVO current(AuthPrincipal principal);
}
