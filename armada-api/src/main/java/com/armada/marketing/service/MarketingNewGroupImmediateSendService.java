package com.armada.marketing.service;

import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import java.util.List;

/**
 * 发送中账号动态任务的新群首次即时营销入口。
 */
public interface MarketingNewGroupImmediateSendService {

    /**
     * 为账号本次新增群抢占并写入一次即时营销命令。
     *
     * <p>同任务、同账号 target、同群 JID 只允许一条 {@code round_no=0} attempt；
     * 本方法不推进任务正常轮次，也不修改下一轮时间。</p>
     *
     * @param accountId  发现新群的账号 ID
     * @param groups     本次新增群，按检测顺序排列
     * @param detectedAt 检测时间(epoch 毫秒)
     */
    void enqueueNewGroups(Long accountId, List<MarketingNewGroupDTO> groups, long detectedAt);

    /**
     * 提交一批已经到达计划时间的新群等待记录。
     *
     * <p>调用方按租户、任务和 target 分组；本方法在事务中锁定 WAITING、重新校验发送资格，
     * 然后复用即时发送的消息组装和 Outbox 提交逻辑。</p>
     *
     * @param tenantId 当前批次租户 ID
     * @param marketingTaskId 当前批次营销任务 ID
     * @param attemptIds 待处理的第 0 轮等待记录 ID
     * @param submittedAt 本次到期处理时间(epoch 毫秒)
     */
    void submitDueWaitingAttempts(Long tenantId, Long marketingTaskId, List<Long> attemptIds, long submittedAt);

    /**
     * 为拉群营销刚创建成功的固定群目标生成第 0 轮即时发送。
     *
     * <p>仅任务仍处于发送中且固定目标可发送时入队；同一固定群目标重复调用会通过发送尝试唯一键幂等跳过。</p>
     *
     * @param marketingTaskId 拉群营销统一任务 ID
     * @param targetId 建群成功后创建的固定营销目标 ID
     * @param detectedAt 建群成功时间（epoch 毫秒）
     */
    void enqueueFixedTarget(Long marketingTaskId, Long targetId, long detectedAt);
}
