package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ContactSaveCommand;

/**
 * WhatsApp 联系人保存协议端口。
 */
public interface ContactPort {

    /**
     * 按账号协议事实把一个 WhatsApp 用户保存为联系人。
     *
     * @param command 统一联系人保存命令
     */
    void save(ContactSaveCommand command);
}
