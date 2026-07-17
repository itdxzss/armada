package com.armada.platform.protocol.port;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupInviteResult;

/**
 * 固定操作账号读取 WhatsApp 群邀请链接的协议端口。
 */
public interface GroupInvitePort {

    /**
     * 查询指定群的当前邀请链接。
     *
     * @param account  固定操作账号引用
     * @param groupJid WhatsApp 群 JID
     * @return 协议层返回的群邀请码和完整邀请链接
     * @throws ProtocolException 当参数缺失、邀请链接为空或协议调用失败时抛出
     */
    GroupInviteResult getInvite(ProtocolAccountRef account, String groupJid);
}
