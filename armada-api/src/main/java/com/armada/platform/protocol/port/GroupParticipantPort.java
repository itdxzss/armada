package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.List;

/**
 * 群成员实时查询协议端口。
 */
public interface GroupParticipantPort {

    /**
     * 通过一个已在群内的协议账号实时读取群成员列表。
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          WhatsApp 群 JID
     * @return 协议层返回的成员列表快照
     */
    List<GroupParticipantResult> listParticipants(String protocolAccountId, String groupJid);
}
