package com.armada.account.model.vo;

/**
 * 单账号上线受理回执。
 *
 * <p>{@code accepted=true} 只表示协议层已受理并开始连接,不表示账号已经 ONLINE。
 * 真正登录状态由后续 Kafka 状态回写切片更新。</p>
 *
 * @param accountId          armada 账号主键
 * @param protocolAccountId  协议层账号句柄
 * @param accepted           协议层是否已受理上线请求
 * @param stateSource        协议层状态来源
 * @param syncedAt           协议层受理时间(epoch 毫秒)
 * @param ownerWorkerId      协议层归属 worker
 * @param ownerEndpoint      归属 worker endpoint;本地归属时可能为空
 * @param currentWorkerId    实际受理 worker
 * @param local              是否本地 worker 受理
 */
public record AccountOnlineVO(
        Long accountId,
        String protocolAccountId,
        boolean accepted,
        String stateSource,
        Long syncedAt,
        String ownerWorkerId,
        String ownerEndpoint,
        String currentWorkerId,
        boolean local
) {
}
