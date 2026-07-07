package com.armada.group.model.dto;

import java.util.List;

/**
 * 创建 WhatsApp 群请求。
 *
 * @param accountId     本地账号 ID
 * @param subject       群名称
 * @param participants  初始成员;支持裸手机号或完整用户 JID
 */
public record GroupCreateDTO(
        Long accountId,
        String subject,
        List<String> participants
) {
}
