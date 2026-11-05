package com.armada.account.service;

import com.armada.account.mapper.AccountMutualChatMapper;
import com.armada.account.mapper.AccountMutualContactMapper;
import com.armada.account.model.dto.AccountMutualChatWork;
import com.armada.account.model.entity.AccountMutualChatItem;
import com.armada.account.model.entity.AccountMutualChatTask;
import com.armada.account.model.enums.AccountMutualChatDirection;
import com.armada.account.model.enums.AccountMutualChatStatus;
import com.armada.account.model.enums.AccountMutualChatTaskStatus;
import com.armada.admin.service.CurrentIdentityService;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每次只提交一条账号方向操作，行锁下同时检查跨任务在途状态。 */
@Service
public class AccountMutualChatTransactions {
    /** 单任务同时在途上限；同一账号仍串行，两组各 50 个号可全部同时执行。 */
    private static final int MAX_IN_FLIGHT_PER_TASK = 100;
    /** 限流结果的最短等待时间。 */
    private static final long RATE_LIMIT_DELAY_MS = 60_000L;
    private final AccountMutualChatMapper mapper;
    private final AccountMutualContactMapper accounts;
    private final MessageSendPort outbox;
    private final CurrentIdentityService identities;
    /** 复用账号行锁、身份服务与消息端口执行同事务派发。 */
    public AccountMutualChatTransactions(AccountMutualChatMapper mapper,
            MessageSendPort outbox, CurrentIdentityService identities, AccountMutualContactMapper accounts) {
        this.mapper = mapper;
        this.accounts = accounts;
        this.outbox = outbox;
        this.identities = identities;
    }
    /** 锁任务再锁账号，不在事务内等待 Kafka 或 WhatsApp。 */
    @Transactional(rollbackFor = Exception.class)
    public void dispatch(AccountMutualChatWork work, long now) {
        var task = mapper.lockTask(work.taskId());
        if (task == null || task.getStatus() == AccountMutualChatTaskStatus.STOPPED.code()
                || task.getStatus() == AccountMutualChatTaskStatus.COMPLETED.code())
            return;
        accounts.lockAccount(work.actorId());
        if (mapper.actorBlocked(work.actorId(), now) > 0)
            return;
        // MySQL REPEATABLE READ：首次一致性读必须在取得跨任务账号锁之后，不能读到等待锁前的旧快照。
        if (mapper.stats(task.getId()).submitted() >= MAX_IN_FLIGHT_PER_TASK)
            return;
        var item = mapper.firstPending(task.getId(), work.actorId());
        if (item == null)
            return;
        var identity = identities.load(task.getCreatedBy(), task.getTenantId());
        var actor = accounts.account(item.getActorId());
        var target = accounts.account(item.getTargetId());
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
                    new Observation(AccountMutualChatStatus.FAILED, reason, null),
                    now, task.getIntervalSeconds());
            refresh(task, now);
            return;
        }
        item.setAttemptNo(item.getAttemptNo() + 1);
        item.setStatus(AccountMutualChatStatus.SUBMITTED.code());
        item.setProtocolMessageId(null);
        item.setReasonCode(null);
        item.setSubmittedAt(now);
        item.setResultAt(null);
        item.setUpdatedAt(now);
        item.setCommandId("cmd_" + UUID.randomUUID().toString().replace("-", ""));
        var result = outbox.enqueue(List.of(command(task, item)));
        if (result == null || result.items().size() != 1
                || !Objects.equals(result.items().getFirst().commandId(), item.getCommandId()))
            throw new BusinessException(ErrorCode.CONFLICT, "互聊入队结果缺少命令关联");
        var accepted = result.items().getFirst();
        if (!accepted.accepted()) {
            finish(item, new Observation(AccountMutualChatStatus.FAILED,
                    accepted.reasonCode() == null ? "ENQUEUE_REJECTED" : accepted.reasonCode(), null),
                    now, task.getIntervalSeconds());
            refresh(task, now);
            return;
        }
        mapper.updateItem(item);
        mapper.setTaskStatus(task.getId(), AccountMutualChatTaskStatus.RUNNING.code(), now);
    }
    /** 回执按当前命令和完整业务关联结算，待确认允许明确晚到结果升级。 */
    @Transactional(rollbackFor = Exception.class)
    public void apply(ProtocolMessageSendResultReportedEvent event) {
        if (event == null || !AccountMutualChatPolicy.SOURCE.equals(event.source())
                || event.tenantId() == null || event.mutualChatTaskId() == null || event.commandId() == null)
            return;
        Long previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            var task = mapper.lockTask(event.mutualChatTaskId());
            if (task == null) return;
            var item = mapper.byCommand(event.commandId());
            if (item == null || !Objects.equals(item.getTaskId(), event.mutualChatTaskId())
                    || !Objects.equals(item.getId(), event.mutualChatItemId())
                    || !Objects.equals(item.getActorId(), event.accountId())
                    || !Objects.equals(item.getProtocolAccountId(), event.protocolAccountId())
                    || !Objects.equals(item.getTargetPhone() + "@s.whatsapp.net", event.jid())
                    || !"PRIVATE".equals(event.targetKind())
                    || (item.getStatus() != AccountMutualChatStatus.SUBMITTED.code()
                            && item.getStatus() != AccountMutualChatStatus.UNKNOWN.code())) return;
            var observation = observation(event);
            // 重复 UNKNOWN 不延长冷却；仍允许同命令晚到的明确结果。
            if (item.getStatus() == AccountMutualChatStatus.UNKNOWN.code()
                    && observation.state() == AccountMutualChatStatus.UNKNOWN) return;
            long now = System.currentTimeMillis();
            finish(item, observation, now, task.getIntervalSeconds());
            refresh(task, now);
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.set(previous);
        }
    }

    private static Observation observation(ProtocolMessageSendResultReportedEvent event) {
        if (event.success() && event.messageId() != null && !event.messageId().isBlank())
            return new Observation(AccountMutualChatStatus.SUCCESS, null, event.messageId());
        boolean unknown = "UNKNOWN".equals(event.outcome())
                || List.of("SEND_RESULT_UNKNOWN", "UNKNOWN", "EMPTY_MESSAGE_ID")
                    .contains(Objects.toString(event.reasonCode(), ""));
        return new Observation(unknown ? AccountMutualChatStatus.UNKNOWN : AccountMutualChatStatus.FAILED,
                event.reasonCode() == null ? (unknown ? "SEND_RESULT_UNKNOWN" : "SEND_FAILED") : event.reasonCode(), null);
    }

    private static MessageSendCommand command(AccountMutualChatTask task, AccountMutualChatItem item) {
        String text = item.getDirection() == AccountMutualChatDirection.LEFT_TO_RIGHT.code()
                ? task.getLeftText() : task.getRightText();
        return new MessageSendCommand(
                new ProtocolAccountRef(item.getActorId(), ProtocolBackend.valueOf(item.getProtocolBackend()),
                        item.getProtocolAccountId(), item.getActorPhone()),
                new MessageSendCommand.MessageTarget(item.getTargetPhone() + "@s.whatsapp.net",
                        MessageSendCommand.TargetKind.PRIVATE),
                new MessageSendCommand.MessagePayload(MessageType.TEXT,
                        new MessageSendCommand.MessageContent(text, null, null, null), false, null),
                new MessageSendCommand.MessageCorrelation(task.getTenantId(), AccountMutualChatPolicy.SOURCE,
                        null, null, null, null, null, null, null,
                        new MessageSendCommand.AccountMutualChatCorrelation(task.getId(), item.getId())),
                item.getCommandId(), 0, 0);
    }
    /** 无回执只转待确认，不猜测失败；待确认不阻止该账号继续派发后继方向。 */
    @Transactional(rollbackFor = Exception.class)
    public void expire(Long taskId, long deadline, long now) {
        var task = mapper.lockTask(taskId);
        if (task == null)
            return;
        mapper.expireSubmitted(taskId, deadline, now);
        refresh(task, now);
    }
    private record Observation(AccountMutualChatStatus state, String reason, String messageId) {}

    private void finish(AccountMutualChatItem i, Observation observation, long now, int interval) {
        String reason = observation.reason();
        i.setStatus(observation.state().code());
        i.setReasonCode(reason == null ? "" : reason.substring(0, Math.min(64, reason.length())));
        i.setProtocolMessageId(observation.messageId());
        i.setResultAt(now);
        i.setUpdatedAt(now);
        long next = AccountMutualChatPolicy.nextAt(now, interval);
        if (reason != null && (reason.contains("RATE") || reason.contains("LIMIT")))
            next = Math.max(next, now + RATE_LIMIT_DELAY_MS);
        i.setNextExecuteAt(next);
        mapper.updateItem(i);
    }
    private void refresh(AccountMutualChatTask task, long now) {
        if (task.getStatus() == AccountMutualChatTaskStatus.STOPPED.code())
            return;
        var stats = mapper.stats(task.getId());
        int status = stats.success() == stats.total() ? AccountMutualChatTaskStatus.COMPLETED.code()
                : stats.unknown() > 0 || (stats.pending() == 0 && stats.submitted() == 0)
                ? AccountMutualChatTaskStatus.ATTENTION.code()
                : AccountMutualChatTaskStatus.RUNNING.code();
        mapper.setTaskStatus(task.getId(), status, now);
    }
}
