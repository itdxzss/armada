package com.armada.platform.protocol.model.result;

import java.util.List;

/**
 * 一次账号通讯录读取的协议事实。
 *
 * <p>快照时间不在本类里：它由协议事件的 snapshotCutoff 直接带到落库层，
 * 归一化器不需要也不应该看到它。</p>
 *
 * @param contacts 联系人列表，协议层已做号码归一
 */
public record AccountContactSnapshot(List<Contact> contacts) {

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
