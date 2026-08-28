package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;

/**
 * WhatsApp 账号通讯录读取协议端口。
 */
public interface ContactListPort {

    /**
     * 读取指定账号当前可得的通讯录。
     *
     * @param account 统一协议账号引用
     * @return 通讯录快照
     */
    AccountContactSnapshot list(ProtocolAccountRef account);
}
