package com.armada.group.service;

import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;

/** 固化历史群与账号上控后群事实。 */
public interface GroupClassificationService {

    /**
     * 登记首次 baseline 群并把历史事实提升为真。
     *
     * @param groups 首次 baseline 群
     * @param observedBackend 本次观察协议后端
     * @param now 更新时间(epoch 毫秒)
     */
    void captureHistoricalBaseline(List<GroupClassificationCandidate> groups,
                                   ProtocolBackend observedBackend,
                                   long now);

    /** phase1 仅固化历史分类，任务留待当前绑定事务提交。 */
    GroupClassificationPlan stageHistoricalBaseline(
            List<GroupClassificationCandidate> groups,
            ProtocolBackend observedBackend,
            long now);

    /**
     * 按已固化 baseline 分类当前完整或增量可见群。
     *
     * @param accountId 账号 ID
     * @param groups 已登记群入口
     * @param now 更新时间(epoch 毫秒)
     */
    void classifyVisibleGroups(Long accountId,
                               List<GroupClassificationCandidate> groups,
                               long now);

    /** phase1 仅固化当前可见群分类，任务留待当前绑定事务提交。 */
    GroupClassificationPlan stageVisibleGroups(
            Long accountId,
            List<GroupClassificationCandidate> groups,
            long now);

    /**
     * 按可靠 self add 事实分类单个上控后群。
     *
     * @param accountId 账号 ID
     * @param group 已登记群入口
     * @param occurredAt 协议事实时间(epoch 毫秒)
     * @param now 更新时间(epoch 毫秒)
     */
    void classifyMembershipAdded(Long accountId,
                                 GroupClassificationCandidate group,
                                 long occurredAt,
                                 long now);
}
