package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.GroupCreateResult;
import java.util.List;

/**
 * WhatsApp 建群协议端口。
 */
public interface GroupCreatePort {

    /**
     * 指定账号创建 WhatsApp 群并带初始成员。
     *
     * @param protocolAccountId 协议层账号句柄,如 acc_8613800138000
     * @param subject           群名称
     * @param participants      初始成员;支持裸手机号或完整用户 JID
     * @return 协议层建群结果
     */
    default GroupCreateResult create(String protocolAccountId, String subject, List<String> participants) {
        return create(protocolAccountId, subject, participants, false);
    }

    /**
     * 指定账号创建 WhatsApp 群并带初始成员。
     *
     * @param protocolAccountId 协议层账号句柄,如 acc_8613800138000
     * @param subject           群名称
     * @param participants      初始成员;支持裸手机号或完整用户 JID
     * @param announceOnly      是否在建群后请求切换为仅管理员发言;请求失败不阻断建群结果
     * @return 协议层建群结果
     */
    GroupCreateResult create(String protocolAccountId, String subject, List<String> participants, boolean announceOnly);
}
