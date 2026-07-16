package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.GroupMetadataResult;

/** WhatsApp 群详情实时查询协议端口。 */
public interface GroupMetadataPort {

    /**
     * 查询指定账号可见的群详情。
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          群 JID
     * @return 稳定群详情
     */
    GroupMetadataResult getMetadata(String protocolAccountId, String groupJid);
}
