package com.armada.marketing.script.service;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.marketing.mapper.ScriptMarketingTaskMapper;
import com.armada.marketing.mapper.ScriptMarketingGroupMapper;
import com.armada.marketing.mapper.ScriptMarketingSendRecordMapper;
import com.armada.marketing.model.entity.ScriptMarketingTask;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.model.entity.ScriptMarketingSendRecord;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.platform.protocol.port.ScriptMessageControlPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.CLOSED;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.DRAFT;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.FAILED;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.FINISHED;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.HELD;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.PAUSED;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.RUNNING;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.SENDING;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.SOURCE;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.SUCCESS;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.UNKNOWN;

/** 每群顺序执行；任务行锁统一串行化启动、暂停、调度和结果，所有进度持久化。 */
@Service
public class ScriptMarketingExecutionService {
    /** 结果等待上限；超时关闭等待且不自动重发。 */
    public static final long RESULT_TIMEOUT_MS = 120_000L;
    private final ScriptMarketingTaskMapper tasks;
    private final ScriptMarketingGroupMapper groups;
    private final ScriptMarketingSendRecordMapper records;
    private final ScriptMarketingContentService content;
    private final AccountProtocolLookupService accounts;
    private final MessageSendPort sender;
    private final ScriptMessageControlPort control;
    private final TransactionTemplate resultTransaction;
    /** 注入真实持久化与既有发送端口。 */
    public ScriptMarketingExecutionService(ScriptMarketingTaskMapper tasks, ScriptMarketingGroupMapper groups,
            ScriptMarketingSendRecordMapper records, ScriptMarketingContentService content,
            AccountProtocolLookupService accounts, MessageSendPort sender, ScriptMessageControlPort control,
            PlatformTransactionManager transactionManager) {
        this.tasks = tasks; this.groups = groups; this.records = records; this.content = content;
        this.accounts = accounts; this.sender = sender; this.control = control;
        this.resultTransaction = new TransactionTemplate(transactionManager);
        this.resultTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    /** 在当前租户内复核到期群，最多提交一个未处理项；多实例重复扫描不会重复提交。 */
    @Transactional(rollbackFor = Exception.class)
    public void tick(Long taskId, Long groupId, long now) {
        var task = tasks.lock(taskId);
        if (task == null || (task.getStatus() != RUNNING && task.getStatus() != PAUSED)) return;
        if (task.getEndAt() != null && task.getEndAt() <= now) { close(task, now); return; }
        var group = groups.find(groupId);
        if (group == null || !taskId.equals(group.getTaskId()) || group.getNextAt() > now) return;
        var steps = content.decode(task.getStepsJson());
        if (group.getNextStep() >= steps.size()) return;
        var row = records.findStep(groupId, group.getNextStep());
        if (row != null) {
            if (row.getStatus() == SENDING && row.getSubmittedAt() + RESULT_TIMEOUT_MS <= now) {
                boolean unsent = control.expire(row.getCommandId());
                finish(row, unsent ? FAILED : UNKNOWN,
                        unsent ? "等待投递超时，已取消未发送命令" : "等待结果超时，结果未知，不自动重发", null, now);
                advance(task, group, steps.size(), now);
            }
            return;
        }
        if (task.getStatus() == RUNNING) submit(task, group, steps, now);
    }
    /** 调度事务回滚后记录未提交失败；若原意图已存在，则继续跟踪原命令，不能覆盖或重发。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordUnsubmittedFailure(Long taskId, Long groupId, int stepIndex, long now, String reason) {
        var task = tasks.lock(taskId);
        if (task == null || (task.getStatus() != RUNNING && task.getStatus() != PAUSED)) return;
        var group = groups.find(groupId);
        if (group == null || !taskId.equals(group.getTaskId()) || group.getNextStep() != stepIndex
                || records.findStep(groupId, stepIndex) != null) return;
        var steps = content.decode(task.getStepsJson());
        if (stepIndex >= steps.size()) return;
        var row = insertIntent(task, group, steps.get(stepIndex).accountId(), now);
        finish(row, FAILED, reason, null, now);
        advance(task, group, steps.size(), now);
    }
    /** 用户显式操作；重复相同动作幂等，错误状态不会重启任务。 */
    @Transactional(rollbackFor = Exception.class)
    public void action(Long id, String action, Long owner) {
        var task = ScriptMarketingTaskService.requireOwned(tasks.lock(id), owner);
        long now = System.currentTimeMillis();
        switch (action) {
            case "start" -> start(task, now);
            case "pause" -> pause(task, now);
            case "resume" -> resume(task, now);
            case "close" -> close(task, now);
            default -> throw new BusinessException(ErrorCode.VALIDATION, "未知任务操作");
        }
    }
    /** 回调按原命令匹配；未知结果允许补记，重复结果不能再次推进进度。 */
    public void result(ProtocolMessageSendResultReportedEvent event) {
        if (Boolean.FALSE.equals(event.terminal())) return;
        var first = records.findCommand(event.commandId());
        if (first == null) return;
        // 归属查询在事务外；新事务先锁任务再读取状态，避免 MySQL RR 快照在等待锁前建立。
        resultTransaction.executeWithoutResult(status -> applyResult(first.getTaskId(), event));
    }
    private void applyResult(Long taskId, ProtocolMessageSendResultReportedEvent event) {
        var task = tasks.lock(taskId);
        var row = records.findCommand(event.commandId());
        if (task == null || row == null || !row.getTaskId().equals(taskId) || (row.getStatus() != SENDING && row.getStatus() != UNKNOWN)) return;
        var group = groups.find(row.getGroupId());
        if (event.groupJid() != null && !group.getGroupJid().equals(event.groupJid())) return;
        boolean advance = row.getStatus() == SENDING;
        long now = System.currentTimeMillis();
        String reason = event.reasonCode() == null ? event.reasonMessage()
                : event.reasonCode() + ": " + (event.reasonMessage() == null ? "" : event.reasonMessage());
        int status = "UNKNOWN".equalsIgnoreCase(event.outcome()) ? UNKNOWN : event.success() ? SUCCESS : FAILED;
        finish(row, status, reason, event.messageId(), now);
        if (advance && group.getNextStep().equals(row.getStepIndex())) {
            advance(task, group, content.decode(task.getStepsJson()).size(), now);
        }
    }
    private void submit(ScriptMarketingTask task, ScriptMarketingGroup group,
            List<ScriptMarketingStepDTO> steps, long now) {
        var step = steps.get(group.getNextStep());
        var row = insertIntent(task, group, step.accountId(), now);
        MessageSendCommand command;
        try {
            var account = accounts.findOnlineProtocolRefs(List.of(step.accountId())).stream().findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION, "固定账号不在线或不可用"));
            command = new MessageSendCommand(account, new MessageSendCommand.MessageTarget(group.getGroupJid()),
                    content.payload(step), new MessageSendCommand.MessageCorrelation(task.getTenantId(), SOURCE,
                    null, null, null, null, null, row.getId()), row.getCommandId(),
                    MessageSendCommand.DEFAULT_SEND_INTERVAL_MS, 0L);
        } catch (BusinessException ex) {
            finish(row, FAILED, ex.getMessage(), null, now);
            advance(task, group, steps.size(), now);
            return;
        }
        // outbox 与意图同事务；基础设施异常整体回滚，不能留下已发送却无记录的状态。
        var result = sender.enqueue(List.of(command));
        var accepted = result.items().stream().filter(item -> row.getCommandId().equals(item.commandId())).findFirst();
        if (accepted.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT, "发送队列未返回原命令结果");
        if (!accepted.get().accepted()) {
            finish(row, FAILED, accepted.get().reasonCode() + ": " + accepted.get().reasonMessage(), null, now);
            advance(task, group, steps.size(), now);
            return;
        }
        group.setNextAt(now + RESULT_TIMEOUT_MS); groups.update(group);
    }
    private ScriptMarketingSendRecord insertIntent(ScriptMarketingTask task, ScriptMarketingGroup group,
            Long accountId, long now) {
        var row = new ScriptMarketingSendRecord();
        row.setTenantId(task.getTenantId()); row.setTaskId(task.getId()); row.setGroupId(group.getId());
        row.setStepIndex(group.getNextStep()); row.setAccountId(accountId);
        row.setCommandId("cmd_" + UUID.randomUUID().toString().replace("-", ""));
        row.setStatus(SENDING); row.setSubmittedAt(now);
        records.insert(row);
        return row;
    }
    private void start(ScriptMarketingTask task, long now) {
        if (task.getStatus() == RUNNING) return;
        requireState(task, DRAFT, "只能启动草稿任务");
        requireBeforeEnd(task, now);
        content.validate(content.decode(task.getStepsJson()));
        state(task, RUNNING, now);
    }
    private void pause(ScriptMarketingTask task, long now) {
        if (task.getStatus() == PAUSED) return;
        requireState(task, RUNNING, "只有运行中的任务可以暂停");
        state(task, PAUSED, now);
        for (var group : groups.list(task.getId())) {
            var row = records.findStep(group.getId(), group.getNextStep());
            if (row != null && row.getStatus() == SENDING) {
                if (!control.hold(row.getCommandId())) continue;
                row.setStatus(HELD); records.update(row);
            }
            group.setRemainingWaitMs(Math.max(0, group.getNextAt() - now));
            group.setNextAt(Long.MAX_VALUE); groups.update(group);
        }
    }
    private void resume(ScriptMarketingTask task, long now) {
        if (task.getStatus() == RUNNING) return;
        requireState(task, PAUSED, "只有暂停任务可以继续");
        requireBeforeEnd(task, now);
        state(task, RUNNING, now);
        int size = content.decode(task.getStepsJson()).size();
        for (var group : groups.list(task.getId())) {
            if (group.getNextStep() >= size) continue;
            var row = records.findStep(group.getId(), group.getNextStep());
            if (row != null && row.getStatus() == SENDING) continue;
            if (row != null && row.getStatus() == HELD) {
                if (!control.resume(row.getCommandId())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "原暂停命令状态已变化，请刷新后重试");
                }
                row.setStatus(SENDING); row.setSubmittedAt(now); records.update(row);
                group.setNextAt(now + RESULT_TIMEOUT_MS);
            } else {
                group.setNextAt(now + group.getRemainingWaitMs());
            }
            group.setRemainingWaitMs(0L); groups.update(group);
        }
    }
    private void close(ScriptMarketingTask task, long now) {
        if (task.getStatus() == CLOSED || task.getStatus() == FINISHED) return;
        state(task, CLOSED, now);
        for (var group : groups.list(task.getId())) {
            var row = records.findStep(group.getId(), group.getNextStep());
            if (row != null && (row.getStatus() == SENDING || row.getStatus() == HELD)) {
                boolean unsent = control.expire(row.getCommandId());
                finish(row, unsent ? FAILED : UNKNOWN,
                        unsent ? "任务已关闭，未发送命令已取消" : "任务已关闭，在途结果仍待确认", null, now);
            }
            group.setNextAt(Long.MAX_VALUE); groups.update(group);
        }
    }
    private void advance(ScriptMarketingTask task, ScriptMarketingGroup group, int size, long now) {
        group.setNextStep(group.getNextStep() + 1);
        long wait = task.getIntervalSeconds() * 1000L;
        group.setRemainingWaitMs(task.getStatus() == PAUSED ? wait : 0L);
        boolean runnable = task.getStatus() == RUNNING && group.getNextStep() < size;
        group.setNextAt(runnable ? now + wait : Long.MAX_VALUE); groups.update(group);
        if ((task.getStatus() == RUNNING || task.getStatus() == PAUSED)
                && groups.list(task.getId()).stream().allMatch(item -> item.getNextStep() >= size)) {
            state(task, FINISHED, now);
        }
    }
    private void finish(ScriptMarketingSendRecord row, int status, String reason, String messageId, long now) {
        row.setStatus(status); row.setReason(reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
        row.setMessageId(messageId); row.setFinishedAt(now); records.update(row);
    }
    private void state(ScriptMarketingTask task, int status, long now) {
        task.setStatus(status); task.setUpdatedAt(now); tasks.updateState(task);
    }
    private void requireState(ScriptMarketingTask task, int expected, String message) {
        if (task.getStatus() != expected) throw new BusinessException(ErrorCode.CONFLICT, message);
    }
    private void requireBeforeEnd(ScriptMarketingTask task, long now) {
        if (task.getEndAt() != null && task.getEndAt() <= now) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已超过截止时间");
        }
    }
}
