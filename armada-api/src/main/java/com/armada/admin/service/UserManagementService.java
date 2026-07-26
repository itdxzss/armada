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

    void changeStatus(long id, Integer status);
}
