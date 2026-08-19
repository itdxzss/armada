package com.armada.task.service.impl;

import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.JoinTaskResultReportedEvent;
import com.armada.task.model.dto.JoinTaskDeadCommandCandidate;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.JoinTaskFailureReason;
import com.armada.task.service.JoinTaskIntervalPolicy;
import com.armada.task.service.JoinTaskResultService;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Web/Android 统一进群结果状态机实现。
 *
 * <p>每次处理先在事件租户上下文内锁定仍为 SUBMITTED 且 commandId、attemptNo 匹配的明细。重复事件、
 * 旧尝试迟到结果或已经被其它消费者处理的事件查不到可更新行，因此直接幂等返回。当前行需要重试时
 * 只把它恢复为 WAITING 并设置随机执行时间；只有当前行进入终态后才激活同账号下一行。</p>
 *
 * <p>任务计数刷新、下一行激活和当前行状态迁移处于同一事务。协议事件自带 timestamp 仅供诊断，
 * 业务排期使用 Armada 当前时间，避免协议机器时钟偏差破坏账号间隔。</p>
 */
@Service
public class JoinTaskResultServiceImpl implements JoinTaskResultService {

    /** 协议已成功加入目标群。 */
    private static final String OUTCOME_JOINED = "JOINED";

    /** 协议确认账号此前已在目标群，业务上按成功收敛。 */
    private static final String OUTCOME_ALREADY_JOINED = "ALREADY_JOINED";

    /** 目标群开启入群审批，本次命令已结束但未真正入群。 */
    private static final String OUTCOME_PENDING_APPROVAL = "PENDING_APPROVAL";

    /** 协议明确报告本次尝试失败。 */
    private static final String OUTCOME_FAILED = "FAILED";

    /** 进群明细持久化入口，负责带条件的幂等状态迁移。 */
    private final JoinTaskResultMapper resultMapper;

    /** 任务持久化入口，用于读取重试配置并刷新聚合计数。 */
    private final JoinTaskMapper taskMapper;

    /** 同账号下一次执行时间的随机区间策略。 */
    private final JoinTaskIntervalPolicy intervalPolicy;

    /** 进群成功后的延迟营销登记入口；延迟未开启时由该服务直接忽略。 */
    private final MarketingNewGroupImmediateSendService marketingNewGroupService;

    /** 可替换时钟，生产使用系统时间，测试使用固定时间。 */
    private final LongSupplier currentTimeMillis;

    /**
     * 创建使用系统时钟的结果状态机。
     *
     * @param resultMapper 进群明细 Mapper
     * @param taskMapper 进群任务 Mapper
     * @param intervalPolicy 随机执行间隔策略
     * @param marketingNewGroupService 新群延迟营销登记服务
     */
    @Autowired
    public JoinTaskResultServiceImpl(JoinTaskResultMapper resultMapper,
                                     JoinTaskMapper taskMapper,
                                     JoinTaskIntervalPolicy intervalPolicy,
                                     MarketingNewGroupImmediateSendService marketingNewGroupService) {
        this(resultMapper, taskMapper, intervalPolicy, marketingNewGroupService, System::currentTimeMillis);
    }

    /**
     * 创建可注入时钟的结果状态机，供确定性单元测试使用。
     *
     * @param resultMapper 进群明细 Mapper
     * @param taskMapper 进群任务 Mapper
     * @param intervalPolicy 随机执行间隔策略
     * @param marketingNewGroupService 新群延迟营销登记服务
     * @param currentTimeMillis 当前 epoch 毫秒提供器
     */
    public JoinTaskResultServiceImpl(JoinTaskResultMapper resultMapper,
                                     JoinTaskMapper taskMapper,
                                     JoinTaskIntervalPolicy intervalPolicy,
                                     MarketingNewGroupImmediateSendService marketingNewGroupService,
                                     LongSupplier currentTimeMillis) {
        this.resultMapper = resultMapper;
        this.taskMapper = taskMapper;
        this.intervalPolicy = intervalPolicy;
        this.marketingNewGroupService = marketingNewGroupService;
        this.currentTimeMillis = currentTimeMillis;
    }

    /**
     * {@inheritDoc}
     *
     * <p>状态锁定条件同时包含明细 ID、命令 ID 和尝试序号；不匹配表示重复或迟到消息，按幂等成功
     * 返回。只有 FAILED 且任务开启重试、协议标记可重试、尝试次数未超过上限时才重新排期。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(JoinTaskResultReportedEvent event) {
        validate(event);
        Long previousTenant = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            JoinTaskResult row = resultMapper.selectSubmittedForUpdate(
                    event.joinTaskResultId(), event.commandId(), event.attemptNo());
            if (row == null) {
                return;
            }
            if (!event.joinTaskId().equals(row.getJoinTaskId())
                    || !event.accountId().equals(row.getAccountId())) {
                return;
            }
            JoinTask task = taskMapper.selectByTenantAndId(row.getJoinTaskId());
            if (task == null) {
                return;
            }
            long now = currentTimeMillis.getAsLong();
            String outcome = event.outcome().trim().toUpperCase(Locale.ROOT);
            if (OUTCOME_JOINED.equals(outcome) || OUTCOME_ALREADY_JOINED.equals(outcome)) {
                String groupJid = OUTCOME_JOINED.equals(outcome)
                        ? requiredJoinedGroupJid(event.groupJid())
                        : safe(event.groupJid());
                resultMapper.markTerminalSuccess(row.getId(), groupJid, now);
                if (OUTCOME_JOINED.equals(outcome)) {
                    marketingNewGroupService.enqueueDelayedNewGroups(
                            row.getAccountId(),
                            List.of(new MarketingNewGroupDTO(null, groupJid, null)),
                            now);
                }
                advanceAfterTerminal(task, row, now);
                return;
            }
            if (OUTCOME_PENDING_APPROVAL.equals(outcome)) {
                resultMapper.markTerminalFailure(
                        row.getId(), JoinTaskFailureReason.JOIN_PENDING_APPROVAL.code(), now);
                advanceAfterTerminal(task, row, now);
                return;
            }
            if (!OUTCOME_FAILED.equals(outcome)) {
                throw new IllegalArgumentException("不支持的进群结果 outcome");
            }
            String reason = failureReason(event.reasonCode());
            if (task.isRetryEnabled() && event.retryable() && event.attemptNo() <= task.getRetryLimit()) {
                resultMapper.markRetry(row.getId(), reason, intervalPolicy.nextExecuteAt(task, now), now);
                return;
            }
            resultMapper.markTerminalFailure(row.getId(), reason, now);
            advanceAfterTerminal(task, row, now);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>DEAD 只表示 Armada 到 Kafka 的传输重试耗尽，不代表 WhatsApp 业务失败。当前尝试仍匹配时，
     * 它按可重试的 KAFKA_PUBLISH_FAILED 进入同一套任务重试规则；旧 DEAD 命令不会影响新尝试。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyTransportFailure(JoinTaskDeadCommandCandidate candidate) {
        if (candidate == null || candidate.tenantId() == null || candidate.resultId() == null
                || candidate.commandId() == null || candidate.commandId().isBlank()
                || candidate.attemptNo() <= 0) {
            throw new IllegalArgumentException("进群发送失败关联字段不完整");
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.tenantId());
        try {
            JoinTaskResult row = resultMapper.selectSubmittedForUpdate(
                    candidate.resultId(), candidate.commandId(), candidate.attemptNo());
            if (row == null) {
                return;
            }
            JoinTask task = taskMapper.selectByTenantAndId(row.getJoinTaskId());
            if (task == null) {
                return;
            }
            long now = currentTimeMillis.getAsLong();
            String reason = JoinTaskFailureReason.KAFKA_PUBLISH_FAILED.code();
            if (task.isRetryEnabled() && candidate.attemptNo() <= task.getRetryLimit()) {
                resultMapper.markRetry(row.getId(), reason, intervalPolicy.nextExecuteAt(task, now), now);
            } else {
                resultMapper.markTerminalFailure(row.getId(), reason, now);
                advanceAfterTerminal(task, row, now);
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 在当前行终态之后推进账号 lane，并刷新任务汇总及完成状态。
     *
     * <p>即使不存在下一行也刷新计数；任务只有在所有明细均无 PENDING 时才会标记完成。</p>
     */
    private void advanceAfterTerminal(JoinTask task, JoinTaskResult row, long now) {
        resultMapper.activateNextPending(
                row.getJoinTaskId(), row.getAccountId(), row.getId(), intervalPolicy.nextExecuteAt(task, now), now);
        taskMapper.refreshCounters(row.getJoinTaskId());
        taskMapper.markDoneWhenNoPending(row.getJoinTaskId(), now);
    }

    /** 把空协议原因归一为稳定的 UNKNOWN 业务原因码。 */
    private static String failureReason(String reasonCode) {
        return reasonCode == null || reasonCode.isBlank()
                ? JoinTaskFailureReason.UNKNOWN.code() : reasonCode.trim();
    }

    /** 把协议可空文本归一为空串，满足历史非空列约束。 */
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /** JOINED 必须携带可用于新群营销和结果追踪的群 JID，非法值不能先收敛为成功终态。 */
    private static String requiredJoinedGroupJid(String value) {
        String groupJid = safe(value).toLowerCase(Locale.ROOT);
        if (groupJid.length() <= "@g.us".length()
                || !groupJid.endsWith("@g.us")
                || groupJid.indexOf('@') != groupJid.lastIndexOf('@')) {
            throw new IllegalArgumentException("进群成功结果 groupJid 非法");
        }
        return groupJid;
    }

    /** 在设置租户上下文前校验锁定当前业务尝试所需的最小关联字段。 */
    private static void validate(JoinTaskResultReportedEvent event) {
        if (event == null
                || event.tenantId() == null
                || event.joinTaskId() == null
                || event.joinTaskResultId() == null
                || event.accountId() == null
                || event.commandId() == null
                || event.commandId().isBlank()
                || event.attemptNo() <= 0
                || event.outcome() == null
                || event.outcome().isBlank()) {
            throw new IllegalArgumentException("进群结果事件缺少必要字段");
        }
    }
}
