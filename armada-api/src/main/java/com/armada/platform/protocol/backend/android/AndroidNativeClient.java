package com.armada.platform.protocol.backend.android;

import java.util.List;

/**
 * Android Zhuan 原生 HTTP 能力入口。
 *
 * <p>本接口保留原生响应包，具体状态、进群和成员语义由对应 adapter 解码。</p>
 */
public interface AndroidNativeClient {

    /**
     * 查询 Android 协议账号原生运行态。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @return Android 原生响应包
     */
    AndroidResponseEnvelope status(String wsPhone);

    /**
     * 通过邀请码发起 Android 原生进群。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param inviteCode WhatsApp 群邀请码
     * @return Android 原生响应包
     */
    AndroidResponseEnvelope join(String wsPhone, String inviteCode);

    /**
     * 查询 Android 协议账号可见的目标群成员。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param groupJid 带 {@code @g.us} 的 WhatsApp 群 JID
     * @return Android 原生响应包
     */
    AndroidResponseEnvelope members(String wsPhone, String groupJid);

    /**
     * 使用 Android 原生接口保存联系人。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param numbers 待保存的联系人号码
     * @return Android 原生响应包
     */
    AndroidResponseEnvelope saveContacts(String wsPhone, List<String> numbers);

    /**
     * 使用 Android 原生接口创建群组。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param subject 群名称
     * @param participants 初始成员 JID
     * @return Android 原生响应包
     */
    AndroidResponseEnvelope createGroup(
            String wsPhone,
            String subject,
            List<String> participants);

    /**
     * 使用 Android 原生接口设置普通成员是否可以发言。
     *
     * @param wsPhone 不带加号的纯数字 WhatsApp 手机号
     * @param groupJid 带 {@code @g.us} 的 WhatsApp 群 JID
     * @param membersCanSend 普通成员是否可以发言
     * @return Android 原生响应包
     */
    AndroidResponseEnvelope setGroupAnnouncement(
            String wsPhone,
            String groupJid,
            boolean membersCanSend);

    /** 使用 Android 原生接口批量添加群成员。 */
    AndroidResponseEnvelope addGroupMembers(
            String wsPhone,
            String groupJid,
            List<String> participants);

    /** 使用 Android 原生接口设置或取消群管理员。 */
    AndroidResponseEnvelope setGroupAdmin(
            String wsPhone,
            String groupJid,
            String participant,
            boolean enabled);

    /** 使用 Android 原生接口获取群邀请链接。 */
    AndroidResponseEnvelope groupInvite(String wsPhone, String groupJid);

    /** 使用 Android 原生接口退出群组。 */
    AndroidResponseEnvelope leaveGroup(String wsPhone, String groupJid);
}
