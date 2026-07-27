package com.armada.admin.service;

import com.armada.admin.model.dto.MenuCreateDTO;
import com.armada.admin.model.dto.MenuUpdateDTO;
import com.armada.admin.model.vo.MenuRouteVO;
import com.armada.admin.model.vo.MenuTreeVO;
import java.util.List;

/** 租户菜单管理与有效权限树计算。 */
public interface MenuManagementService {

    List<MenuTreeVO> tree();

    MenuTreeVO create(MenuCreateDTO request);

    MenuTreeVO update(long id, MenuUpdateDTO request);

    void changeStatus(long id, Integer status);

    List<MenuRouteVO> findEffectiveRoutesForUser(long userId);
}
