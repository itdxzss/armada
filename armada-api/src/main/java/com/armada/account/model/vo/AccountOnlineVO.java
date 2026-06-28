package com.armada.account.model.vo;

/**
 * 单账号上线受理回执。
 *
 * <p>{@code accepted=true} 只表示上线命令已进入本地 outbox,不表示账号已经 ONLINE。
 * 真正登录状态由后续 Kafka 状态回写切片更新。</p>
 *
 * @param accountId          armada 账号主键
 * @param protocolAccountId  协议层账号句柄
 * @param accepted           上线命令是否已被本地受理
 * @param stateSource        受理来源;outbox 主路径返回 OUTBOX
 * @param syncedAt           本地受理时间(epoch 毫秒)
 * @param ownerWorkerId      协议 worker 归属;outbox 阶段为空
 * @param ownerEndpoint      归属 worker endpoint;outbox 阶段为空
 * @param currentWorkerId    实际受理 worker;outbox 阶段为空
 * @param local              是否本地 worker 受理;outbox 阶段为 false
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
