package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;

/**
 * 单一协议后端的固定账号只读群 metadata 能力。
 */
public interface FixedAccountGroupMetadataBackend {

    /**
     * 返回当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 查询固定账号可见的群详情。
     *
     * @param account 固定操作账号引用
     * @param groupJid 群 JID
     * @return 稳定群详情
     */
    GroupMetadataResult getMetadata(ProtocolAccountRef account, String groupJid);
}
