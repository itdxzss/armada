package com.armada.admin.service;

import com.armada.admin.model.dto.RoleCreateDTO;
import com.armada.admin.model.dto.RoleUpdateDTO;
import com.armada.admin.model.vo.RoleVO;
import java.util.List;

/** 租户角色管理。 */
public interface RoleManagementService {

    List<RoleVO> list();

    RoleVO create(RoleCreateDTO request);

    RoleVO update(long id, RoleUpdateDTO request);

    void changeStatus(long id, Integer status);

    List<Long> getMenuIds(long id);

    void replaceMenus(long id, List<Long> menuIds);
}
