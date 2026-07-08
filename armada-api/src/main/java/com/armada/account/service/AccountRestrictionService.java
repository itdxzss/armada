package com.armada.account.service;

/**
 * 账号受限状态服务。
 *
 * <p>封装协议层返回账号 reachout/group create restricted 后的账号状态冻结和下线命令派发。</p>
 */
public interface AccountRestrictionService {

    /**
     * 将建群受限账号标记为受限并提交下线命令。
     *
     * @param accountId         账号 ID
     * @param protocolAccountId 协议层账号 ID;为空时只更新本地状态,不提交下线命令
     * @param reason            协议层限制原因
     * @param occurredAt        受限事件发生时间(epoch 毫秒)
     */
    void markGroupCreateRestricted(Long accountId, String protocolAccountId, String reason, long occurredAt);
}
