package com.armada.account.service;

import com.armada.account.model.vo.AccountPullerRestrictionSnapshot;
import com.armada.account.model.vo.AccountPullerRestrictionSummary;
import java.util.List;
import java.util.Map;

/** 管理账号消息发送/拉人操作限制的统一状态、截止时间和恢复。 */
public interface AccountOperationRestrictionService {

    /**
     * 按协议事实时间记录 24 小时拉手限制。
     *
     * @param accountId 发生限制的账号 ID
     * @param reasonCode 协议返回的限制原因码
     * @param occurredAt 协议事实发生时间(epoch 毫秒)
     * @param now Armada 当前处理时间(epoch 毫秒)
     * @return 截止仍在未来且当前租户账号状态行存在时为 true
     */
    boolean restrictPulling(Long accountId, String reasonCode, long occurredAt, long now);

    /**
     * 按协议事实时间记录 24 小时消息发送限制。
     *
     * @param accountId 发生限制的账号 ID
     * @param reasonCode 协议返回的限制原因码
     * @param occurredAt 协议事实发生时间(epoch 毫秒)
     * @param now Armada 当前处理时间(epoch 毫秒)
     * @return 截止仍在未来且当前租户账号状态行存在时为 true
     */
    boolean restrictMessageSending(
            Long accountId, String reasonCode, long occurredAt, long now);

    /**
     * 跨租户恢复所有已到期账号操作限制，内部按固定批次处理。
     *
     * @param now Armada 当前时间(epoch 毫秒)
     * @return 本轮实际恢复账号数
     */
    int restoreExpired(long now);

    /**
     * 批量读取当前租户账号的拉手限制快照。
     *
     * @param accountIds 账号 ID，可包含空值和重复值
     * @return 以账号 ID 为键的限制快照
     */
    Map<Long, AccountPullerRestrictionSnapshot> findPullerRestrictionsByAccountIds(
            List<Long> accountIds);

    /**
     * 汇总当前租户指定分组仍处于限制状态的账号。
     *
     * @param accountGroupId 账号分组 ID
     * @param serverNow 返回给页面用于校准倒计时的服务端时间
     * @return 受限数量和最近预计恢复时间
     */
    AccountPullerRestrictionSummary summarizePullersByGroupId(
            Long accountGroupId, long serverNow);
}
