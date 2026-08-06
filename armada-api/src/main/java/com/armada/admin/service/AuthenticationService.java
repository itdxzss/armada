package com.armada.admin.service;

import com.armada.admin.model.dto.UserLoginDTO;
import com.armada.admin.model.vo.CurrentAuthVO;
import com.armada.admin.model.vo.UserLoginVO;
import com.armada.shared.security.AuthPrincipal;

/** 管理端真实用户登录与当前身份服务。 */
public interface AuthenticationService {

    /**
     * 校验用户名和密码，创建单会话并返回当前用户；图片验证码校验当前临时关闭。
     *
     * @param request 登录账号和密码，暂时保留验证码字段供后续恢复
     * @return 登录 Token、过期信息和当前用户身份
     * @throws com.armada.shared.exception.BusinessException 账号认证失败时抛出
     */
    UserLoginVO login(UserLoginDTO request);

    /**
     * 把 Spring Security 中已认证的可信身份转为当前用户响应。
     *
     * @param principal Token 过滤器建立的可信身份
     * @return 当前用户、租户、角色和权限信息
     * @throws com.armada.shared.exception.BusinessException 身份不存在时抛出
     */
    CurrentAuthVO current(AuthPrincipal principal);
}
