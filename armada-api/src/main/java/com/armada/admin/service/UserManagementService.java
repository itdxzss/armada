package com.armada.admin.service;

import com.armada.admin.model.dto.UserCreateDTO;
import com.armada.admin.model.dto.UserUpdateDTO;
import com.armada.admin.model.vo.UserVO;
import java.util.List;

/** 租户系统用户管理。 */
public interface UserManagementService {

    List<UserVO> list();

    UserVO get(long id);

    UserVO create(UserCreateDTO request);

    UserVO update(long id, UserUpdateDTO request);

    void resetPassword(long id, String newPassword);

    /**
     * 校验当前密码后修改登录用户自己的密码，并使其已有会话失效。
     *
     * @param id 当前登录用户 ID
     * @param currentPassword 当前密码
     * @param newPassword 符合平台规则的新密码
     * @throws com.armada.shared.exception.BusinessException 当前密码错误或新密码不符合规则时抛出
     */
    void changeOwnPassword(long id, String currentPassword, String newPassword);

    void changeStatus(long id, Integer status);
}
