package com.armada.group.service;

import com.armada.group.model.dto.GroupCreateDTO;
import com.armada.group.model.vo.GroupCreateVO;

/**
 * WhatsApp 群真实操作服务。
 */
public interface GroupOperationService {

    /**
     * 创建 WhatsApp 群并带初始成员。
     *
     * @param dto 创建请求
     * @return 协议层建群结果
     */
    GroupCreateVO createGroup(GroupCreateDTO dto);
}
