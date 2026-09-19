package com.armada.account.service;

import com.armada.account.mapper.AccountMutualContactMapper;
import com.armada.account.model.dto.AccountMutualContactWork;
import com.armada.account.model.entity.AccountMutualContactItem;
import com.armada.account.model.entity.AccountMutualContactTask;
import com.armada.account.model.enums.AccountMutualContactStatus;
import com.armada.account.model.enums.AccountMutualContactTaskStatus;
import com.armada.admin.service.CurrentIdentityService;
import com.armada.platform.kafka.consumer.group.ProtocolMutualContactResult;
import com.armada.platform.kafka.consumer.group.ProtocolMutualContactResultSink;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolMutualContactCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.service.ProtocolMutualContactCommandService;
import com.armada.shared.tenant.TenantContext;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每次只提交一条账号方向操作，行锁下同时检查跨任务在途状态。 */
@Service
public class AccountMutualContactTransactions implements ProtocolMutualContactResultSink {
    private static final int MAX_IN_FLIGHT_PER_TASK = 8;
    private static final long RATE_LIMIT_DELAY_MS = 60_000L;
    private final AccountMutualContactMapper mapper;
    private final ProtocolMutualContactCommandService outbox;
    private final CurrentIdentityService identities;
    public AccountMutualContactTransactions(AccountMutualContactMapper mapper,
            ProtocolMutualContactCommandService outbox, CurrentIdentityService identities) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.identities = identities;
    }
    /** 锁任务再锁账号，不在事务内等待 Kafka 或 WhatsApp。 */
    @Transactional(rollbackFor = Exception.class)
    public void dispatch(AccountMutualContactWork work, long now) {
        var task = mapper.lockTask(work.taskId());
        if (task == null || task.getStatus() == AccountMutualContactTaskStatus.STOPPED.code()
                || task.getStatus() == AccountMutualContactTaskStatus.COMPLETED.code())
            return;
        mapper.lockAccount(work.actorId());
        if (mapper.actorBlocked(work.actorId(), now) > 0)
            return;
        // MySQL REPEATABLE READ：首次一致性读必须在取得跨任务账号锁之后，不能读到等待锁前的旧快照。
        if (mapper.stats(task.getId()).submitted() >= MAX_IN_FLIGHT_PER_TASK)
            return;
        var item = mapper.firstPending(task.getId(), work.actorId());
        if (item == null)
            return;
        var identity = identities.load(task.getCreatedBy(), task.getTenantId());
        var actor = mapper.account(item.getActorId());
        var target = mapper.account(item.getTargetId());
        String reason = "";
        if (identity.isEmpty() || !identity.get().permissions().contains("tenant:account:edit"))
            reason = "ACCESS_REVOKED";
        else if (!AccountMutualContactAccess.owns(identity.get(), actor)
                || !AccountMutualContactAccess.owns(identity.get(), target))
            reason = "ACCOUNT_ACCESS_CHANGED";
        else if (!AccountMutualContactAccess.eligible(actor))
            reason = "ACCOUNT_UNAVAILABLE";
        else if (!Objects.equals(actor.wsPhone(), item.getActorPhone())
                || !Objects.equals(actor.protocolAccountId(), item.getProtocolAccountId())
                || !Objects.equals(actor.protocolBackend(), item.getProtocolBackend())
                || !Objects.equals(target.wsPhone(), item.getTargetPhone()))
            reason = "ACCOUNT_IDENTITY_CHANGED";
        if (!reason.isEmpty()) {
            finish(item,
                    new Observation(AccountMutualContactStatus.FAILED, reason, "ACCOUNT_UNAVAILABLE".equals(reason)),
                    now, task.getIntervalSeconds());
            refresh(task, now);
            return;
        }
        item.setAttemptNo(item.getAttemptNo() + 1);
        item.setStatus(AccountMutualContactStatus.SUBMITTED.code());
        item.setRetryable(false);
        item.setReasonCode(null);
        item.setSubmittedAt(now);
        item.setResultAt(null);
        item.setUpdatedAt(now);
        item.setCommandId(
                outbox.enqueue(new ProtocolMutualContactCommandRequest(task.getTenantId(), task.getId(), item.getId(),
                        new ProtocolAccountRef(item.getActorId(), ProtocolBackend.valueOf(item.getProtocolBackend()),
                                item.getProtocolAccountId(), item.getActorPhone()))));
        mapper.updateItem(item);
        mapper.setTaskStatus(task.getId(), AccountMutualContactTaskStatus.RUNNING.code(), now);
    }
    /** 回执只结算当前 commandId；UNKNOWN 可由同一命令的明确晚到回执升级。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(ProtocolMutualContactResult event) {
        Long previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            var task = mapper.lockTask(event.taskId());
            if (task == null)
                return;
            var i = mapper.item(event.itemId());
            if (i == null || !Objects.equals(i.getTaskId(), event.taskId())
                    || !Objects.equals(i.getCommandId(), event.commandId()) || i.getAttemptNo() != event.attemptNo()
                    || !Objects.equals(i.getActorId(), event.accountId())
                    || !Objects.equals(i.getProtocolAccountId(), event.protocolAccountId())
                    || (i.getStatus() != AccountMutualContactStatus.SUBMITTED.code()
                            && i.getStatus() != AccountMutualContactStatus.UNKNOWN.code()))
                return;
            AccountMutualContactStatus state = switch (event.outcome()) {
                case "SUCCESS" -> AccountMutualContactStatus.SUCCESS;
                case "FAILED" -> AccountMutualContactStatus.FAILED;
                default -> AccountMutualContactStatus.UNKNOWN;
            };
            long now = System.currentTimeMillis();
            finish(i,
                    new Observation(
                            state, event.reasonCode(), state == AccountMutualContactStatus.FAILED && event.retryable()),
                    now, task.getIntervalSeconds());
            refresh(task, now);
        } finally {
            if (previous == null)
                TenantContext.clear();
            else
                TenantContext.set(previous);
        }
    }
    /** 无回执只转待确认并阻止该账号后继派发，不猜测失败。 */
    @Transactional(rollbackFor = Exception.class)
    public void expire(Long taskId, long deadline, long now) {
        var task = mapper.lockTask(taskId);
        if (task == null)
            return;
        mapper.expireSubmitted(taskId, deadline, now);
        refresh(task, now);
    }
    private record Observation(AccountMutualContactStatus state, String reason, boolean retryable) {}

    private void finish(AccountMutualContactItem i, Observation observation, long now, int interval) {
        String reason = observation.reason();
        i.setStatus(observation.state().code());
        i.setReasonCode(reason == null ? "" : reason.substring(0, Math.min(64, reason.length())));
        i.setRetryable(observation.retryable());
        i.setResultAt(now);
        i.setUpdatedAt(now);
        long next = AccountMutualContactPolicy.nextAt(now, interval);
        if (reason != null && (reason.contains("RATE") || reason.contains("LIMIT")))
            next = Math.max(next, now + RATE_LIMIT_DELAY_MS);
        i.setNextExecuteAt(next);
        mapper.updateItem(i);
    }
    private void refresh(AccountMutualContactTask task, long now) {
        if (task.getStatus() == AccountMutualContactTaskStatus.STOPPED.code())
            return;
        var stats = mapper.stats(task.getId());
        int status = stats.success() == stats.total() ? AccountMutualContactTaskStatus.COMPLETED.code()
                : stats.unknown() > 0 || (stats.pending() == 0 && stats.submitted() == 0)
                ? AccountMutualContactTaskStatus.ATTENTION.code()
                : AccountMutualContactTaskStatus.RUNNING.code();
        mapper.setTaskStatus(task.getId(), status, now);
    }
}
