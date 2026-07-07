package com.armada.platform.protocol.port;

/**
 * WhatsApp 联系人保存协议端口。
 */
public interface ContactPort {

    /**
     * 使用指定协议账号把一个 WhatsApp 用户保存为联系人。
     *
     * @param protocolAccountId 协议层账号句柄,如 acc_8613800138000
     * @param contact           裸手机号或完整 WhatsApp 用户 JID
     * @param name              联系人展示名;为空时由 contact 派生
     */
    void saveContact(String protocolAccountId, String contact, String name);
}
