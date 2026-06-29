package com.armada.platform.protocol.model.result;

/**
 * 群邀请链接入群结果。
 *
 * <p>{@code joined=false} 表示 WhatsApp 群开启了新成员审批,账号只进入待审批队列,
 * 不能当作真实入群成功。</p>
 *
 * @param groupJid 群 JID
 * @param joined   true=已入群;false=待管理员审批
 */
public record GroupJoinResult(String groupJid, boolean joined) {
}
