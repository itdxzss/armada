package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupMetadataResult;

/**
 * 固定操作账号只读群 metadata 端口。
 */
public interface FixedAccountGroupMetadataPort {

    /**
     * 查询固定账号可见的群详情。
     *
     * @param account 固定操作账号引用
     * @param groupJid 群 JID
     * @return 稳定群详情
     */
    GroupMetadataResult getMetadata(ProtocolAccountRef account, String groupJid);
}
