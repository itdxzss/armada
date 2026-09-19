package com.armada.task.service;

import com.armada.platform.kafka.consumer.group.ProtocolJoinTaskAdminResult;
import com.armada.platform.kafka.consumer.group.ProtocolJoinTaskAdminResultSink;
import com.armada.platform.protocol.model.command.ProtocolJoinTaskAdminCommandRequest;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskAdminMapper;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.model.dto.JoinTaskAdminObservation;
import com.armada.task.model.dto.JoinTaskAdminWork;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.JoinTaskAdminStatus;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理员阶段短事务；协议查询由调度器在事务外完成。 */
@Service
public class JoinTaskAdminTransactions implements ProtocolJoinTaskAdminResultSink {
    private static final long LEASE_MS = 120_000L;
    private static final long VERIFY_DELAY_MS = 30_000L;
    private static final long RESOURCE_DELAY_MS = 5_000L;
    private final JoinTaskAdminMapper admins;
    private final JoinTaskMapper tasks;
    private final ProtocolCommandOutboxService outbox;
    private final JoinTaskAdminFacts facts;
    private final JoinTaskCleanupTransactions cleanup;

    /** 装配阶段写入、任务汇总与协议命令边界。 */
    public JoinTaskAdminTransactions(JoinTaskAdminMapper admins, JoinTaskMapper tasks,
            ProtocolCommandOutboxService outbox, JoinTaskAdminFacts facts,
            JoinTaskCleanupTransactions cleanup) {
        this.admins = admins; this.tasks = tasks; this.outbox = outbox; this.facts = facts; this.cleanup = cleanup;
    }

    /** 领取明细并设置有界租约，崩溃后可由其他实例恢复。 */
    @Transactional(rollbackFor = Exception.class)
    public Optional<JoinTaskAdminWork> claim(Long id, long now, long timeoutMs) {
        JoinTaskResult row = admins.lock(id);
        if (!pending(row) || row.getAdminNextExecuteAt() == null || row.getAdminNextExecuteAt() > now) return Optional.empty();
        JoinTask task = tasks.selectByTenantAndId(row.getJoinTaskId());
        if (!running(task)) return Optional.empty();
        if (row.getAdminDeadlineAt() == null) row.setAdminDeadlineAt(now + timeoutMs);
        row.setAdminNextExecuteAt(now + LEASE_MS);
        save(row, now);
        return Optional.of(new JoinTaskAdminWork(task, row));
    }

    /** 新鲜角色结论与当前租约匹配后，才允许写命令或终结。 */
    @Transactional(rollbackFor = Exception.class)
    public void observe(JoinTaskAdminWork work, JoinTaskAdminObservation observation, long now) {
        JoinTaskResult row = admins.lock(work.row().getId());
        if (!pending(row) || !Objects.equals(row.getAdminNextExecuteAt(), work.row().getAdminNextExecuteAt())
                || !Objects.equals(row.getAdminCommandId(), work.row().getAdminCommandId())) return;
        JoinTask task = tasks.selectByTenantAndId(row.getJoinTaskId());
        if (!running(task)) return;
        if (observation.kind() == JoinTaskAdminObservation.Kind.SUCCESS) {
            confirm(task, row, now, "join-admin-observe:" + row.getId() + ":" + now, now);
        } else if (observation.kind() == JoinTaskAdminObservation.Kind.FAILED) {
            complete(task, row, false, observation.reason(), now);
        } else if (now >= row.getAdminDeadlineAt()) {
            complete(task, row, false, "设置管理员超时，结果未确认：" + observation.reason(), now);
        } else if (observation.kind() == JoinTaskAdminObservation.Kind.READY) {
            dispatch(task, row, observation, now);
        } else {
            row.setAdminReason(observation.reason());
            row.setAdminNextExecuteAt(now + RESOURCE_DELAY_MS);
            save(row, now);
        }
    }

    private void dispatch(JoinTask task, JoinTaskResult row, JoinTaskAdminObservation observation, long now) {
        // 旧命令未真正发布时不能再产生新命令；收敛超时与 DEAD 统一由下轮处理。
        if (row.getAdminCommandId() != null && !outbox.isJoinTaskAdminCommandSettled(row.getAdminCommandId())) {
            row.setAdminNextExecuteAt(now + VERIFY_DELAY_MS);
            row.setAdminReason("等待管理员命令发送结果");
            save(row, now);
            return;
        }
        if (row.getAdminAttemptNo() > 0 && (!task.isRetryEnabled() || row.getAdminAttemptNo() > task.getRetryLimit())) {
            complete(task, row, false, "设置管理员未生效，重试次数已耗尽", now);
            return;
        }
        var queued = outbox.enqueueJoinTaskAdminCommand(new ProtocolJoinTaskAdminCommandRequest(
                row.getTenantId(), row.getJoinTaskId(), row.getId(), observation.actor()));
        if (queued.inserted() != 1 || queued.commandIds().size() != 1) throw new IllegalStateException("管理员命令未完整入队");
        row.setAdminCommandId(queued.commandIds().get(0));
        row.setAdminAttemptNo(row.getAdminAttemptNo() + 1);
        row.setAdminActorAccountId(observation.actor().armadaAccountId());
        row.setAdminStatus(JoinTaskAdminStatus.SUBMITTED.code());
        row.setAdminNextExecuteAt(now + VERIFY_DELAY_MS);
        row.setAdminReason("");
        save(row, now);
    }

    /** 当前命令的明确成功可终结；UNKNOWN 进入查询，失败按独立次数重试。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(ProtocolJoinTaskAdminResult event) {
        Long previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            JoinTaskResult row = admins.lock(event.joinTaskResultId());
            if (!pending(row) || !Objects.equals(row.getJoinTaskId(), event.joinTaskId())
                    || !Objects.equals(row.getAdminCommandId(), event.commandId())
                    || row.getAdminAttemptNo() != event.attemptNo()
                    || !Objects.equals(row.getAdminActorAccountId(), event.accountId())
                    || !Objects.equals(row.getGroupJid(), event.groupJid())
                    || !facts.targetJid(row).equals(event.targetJid())) return;
            JoinTask task = tasks.selectByTenantAndId(row.getJoinTaskId());
            if (!running(task)) return;
            long now = System.currentTimeMillis();
            if ("SUCCESS".equals(event.outcome())) {
                confirm(task, row, event.timestamp(), event.eventId(), now);
            } else if ("FAILED".equals(event.outcome()) && !event.retryable()
                    && !"GROUP_PERMISSION_DENIED".equals(event.reasonCode())
                    && !"ACCOUNT_NOT_ONLINE".equals(event.reasonCode())) {
                complete(task, row, false, "设置管理员失败：" + event.reasonCode(), now);
            } else {
                row.setAdminStatus(JoinTaskAdminStatus.UNKNOWN.code());
                row.setAdminReason("设置结果待核实：" + event.reasonCode());
                row.setAdminNextExecuteAt(now + VERIFY_DELAY_MS);
                save(row, now);
            }
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }

    private void confirm(JoinTask task, JoinTaskResult row, long occurredAt, String eventId, long now) {
        facts.recordSuccess(row, occurredAt, eventId);
        row.setPromotedAt(occurredAt);
        complete(task, row, true, "", now);
    }

    private void complete(JoinTask task, JoinTaskResult row, boolean success, String reason, long now) {
        outbox.cancelJoinTaskAdminCommand(row.getAdminCommandId(), now);
        row.setAdminStatus(success ? JoinTaskAdminStatus.SUCCESS.code() : JoinTaskAdminStatus.FAILED.code());
        row.setAdmin(success);
        row.setAdminReason(reason.length() > 255 ? reason.substring(0, 255) : reason);
        row.setAdminNextExecuteAt(null);
        save(row, now);
        cleanup.completeAdminStage(task, row, success, now);
    }

    private void save(JoinTaskResult row, long now) {
        row.setUpdatedAt(now);
        if (row.getAdminReason() == null) row.setAdminReason("");
        if (admins.update(row) != 1) throw new IllegalStateException("管理员阶段并发状态已改变");
    }
    private static boolean pending(JoinTaskResult row) {
        return row != null && "SUCCESS".equals(row.getStatus())
                && (row.getAdminStatus() == JoinTaskAdminStatus.WAITING.code()
                    || row.getAdminStatus() == JoinTaskAdminStatus.SUBMITTED.code()
                    || row.getAdminStatus() == JoinTaskAdminStatus.UNKNOWN.code());
    }
    private static boolean running(JoinTask task) {
        return task != null && task.isSetAdminEnabled() && "RUNNING".equals(task.getStatus());
    }
}
