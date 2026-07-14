package com.armada.platform.protocol.backend.android;

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
}
