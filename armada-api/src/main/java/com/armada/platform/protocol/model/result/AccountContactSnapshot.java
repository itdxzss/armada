package com.armada.platform.protocol.model.result;

import java.util.List;

/**
 * 一次账号通讯录读取的协议事实。
 *
 * @param contacts 联系人列表，协议层已做号码归一
 * @param syncedAt 协议层给出的快照时间（epoch 毫秒），可能为空
 */
public record AccountContactSnapshot(List<Contact> contacts, Long syncedAt) {

    /**
     * 单个联系人。
     *
     * @param phone 不带加号的纯数字号码
     * @param jid 规范用户 JID
     * @param fullName 通讯录全名
     * @param firstName 通讯录名
     * @param pushName 对方设置的展示名
     * @param businessName 商业号认证名
     */
    public record Contact(
            String phone,
            String jid,
            String fullName,
            String firstName,
            String pushName,
            String businessName
    ) {
    }
}
