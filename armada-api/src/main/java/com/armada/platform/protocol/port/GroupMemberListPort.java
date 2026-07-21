package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.result.GroupParticipantResult;

import java.util.List;

/**
 * WhatsApp 群成员实时查询协议端口。
 */
public interface GroupMemberListPort {

    /**
     * 按账号协议事实读取群成员列表。
     *
     * @param query 统一群成员列表查询
     * @return 协议层返回的成员列表快照
     */
    List<GroupParticipantResult> list(GroupMemberListQuery query);
}
