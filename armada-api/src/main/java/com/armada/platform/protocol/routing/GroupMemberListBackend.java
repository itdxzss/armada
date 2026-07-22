package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;

import java.util.List;

/**
 * 单一协议后端的群成员列表查询能力。
 */
public interface GroupMemberListBackend {

    /**
     * 返回当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 使用当前协议实现读取群成员列表。
     *
     * @param query 统一群成员列表查询
     * @return 群成员列表快照
     */
    List<GroupParticipantResult> list(GroupMemberListQuery query);
}
