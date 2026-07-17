package com.armada.platform.protocol.model.result;

/**
 * 固定账号在单个群中的 metadata 摘要结果。
 *
 * <p>该模型有意不包含参与者数组，避免历史群列表批量刷新时把完整成员明细带入内存和业务响应。</p>
 *
 * @param groupJid      WhatsApp 群 JID
 * @param success       该群 metadata 是否查询成功
 * @param error         协议层逐群错误；成功且状态正常时为空
 * @param subject       群名称；协议失败时可为空
 * @param memberSize    群成员数；协议失败时可为空
 * @param selfRole      固定账号在群中的角色：OWNER、ADMIN 或 MEMBER
 * @param announceOnly  是否仅管理员可发言；协议失败时可为空
 * @param stateAbnormal 群状态是否异常
 */
public record AccountGroupMetadataSummaryResult(
        String groupJid,
        boolean success,
        String error,
        String subject,
        Integer memberSize,
        String selfRole,
        Boolean announceOnly,
        boolean stateAbnormal
) {
}
