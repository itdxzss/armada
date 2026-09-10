package com.armada.marketing.script.service;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.marketing.mapper.ScriptMarketingTaskMapper;
import com.armada.marketing.mapper.ScriptMarketingGroupMapper;
import com.armada.marketing.mapper.ScriptMarketingSendRecordMapper;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.entity.ScriptMarketingTask;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.model.entity.ScriptMarketingSendRecord;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.platform.protocol.port.ScriptMessageControlPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.CLOSED;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.DRAFT;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.FAILED;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.FINISHED;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.HELD;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.PAUSED;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.RUNNING;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.SENDING;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.SOURCE;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.UNKNOWN;

/**
 * 分组剧本的提交驱动执行器：提交进度与回执分别推进。
 * 所有入口由 ScriptMarketingExecutionService 持任务行锁并提供事务；存量固定账号任务沿用旧语义。
 */
@Service
public class ScriptMarketingPacedExecutionService {
    private final ScriptMarketingTaskMapper tasks;
    private final ScriptMarketingGroupMapper groups;
    private final ScriptMarketingSendRecordMapper records;
    private final ScriptMarketingContentService content;
    private final ScriptQualificationService qualification;
    private final AccountProtocolLookupService accounts;
    private final MessageSendPort sender;
    private final ScriptMessageControlPort control;
    /** 注入既有聚合持久化、资格和原命令控制端口。 */
    public ScriptMarketingPacedExecutionService(ScriptMarketingTaskMapper tasks, ScriptMarketingGroupMapper groups,
            ScriptMarketingSendRecordMapper records, ScriptMarketingContentService content,
            ScriptQualificationService qualification, AccountProtocolLookupService accounts,
            MessageSendPort sender, ScriptMessageControlPort control) {
        this.tasks = tasks; this.groups = groups; this.records = records; this.content = content;
        this.qualification = qualification; this.accounts = accounts; this.sender = sender; this.control = control;
    }
    /** 明确操作只在任务锁内执行；启动检查失败抛异常使绑定和状态全部回滚。 */
    public void action(ScriptMarketingTask task, String action, long now) {
        switch (action) {
            case "start" -> start(task, now);
            case "pause" -> pause(task, now);
            case "resume" -> resume(task, now);
            case "close" -> close(task, now);
            default -> throw new BusinessException(ErrorCode.VALIDATION, "未知任务操作");
        }
    }
    /** 扫描到期回执并最多提交下一项；慢回执不改变下一项的计划时间。 */
    public void tick(ScriptMarketingTask task, Long groupId, long now) {
        if (task.getEndAt() != null && task.getEndAt() <= now) { close(task, now); return; }
        var group = groups.find(groupId);
        if (group == null || !task.getId().equals(group.getTaskId())) return;
        reconcile(task, groupId, now);
        var steps = content.decode(task.getStepsJson());
        if (task.getStatus() != RUNNING || Boolean.TRUE.equals(group.getPaused())
                || group.getNextStep() >= steps.size() || group.getNextAt() > now || task.getStartAt() > now) {
            completeIfSettled(task, now); return;
        }
        // 定时任务第一条实际提交前复核全部目标；失败时整个任务零发送。
        boolean firstSubmission = records.count(task.getId()) == 0;
        var targets = firstSubmission ? groups.list(task.getId()) : List.of(group);
        try {
            var report = qualification.inspect(task.getAccountGroupId(), steps, targets, true).report();
            if (!report.ready()) {
                if (firstSubmission) {
                    pause(task, now);
                    state(task, PAUSED, "所选群资格已变化，请查看检查结果，处理后继续", now);
                }
                else pauseGroup(group, "原绑定账号或群资格发生变化，请查看检查结果", now);
                return;
            }
        } catch (BusinessException exception) {
            if (firstSubmission) { pause(task, now); state(task, PAUSED, exception.getMessage(), now); }
            else pauseGroup(group, exception.getMessage(), now);
            return;
        }
        submit(task, group, steps, now);
    }
    /** 事务回滚后才记录确定未提交的失败；首次资格准备失败必须保持零提交。 */
    public void recordUnsubmittedFailure(ScriptMarketingTask task, ScriptMarketingGroup group, long now, String reason) {
        if (task.getStatus() != RUNNING || Boolean.TRUE.equals(group.getPaused())) return;
        if (records.count(task.getId()) == 0) {
            pause(task, now);
            state(task, PAUSED, "启动准备失败，请重新检查后继续：" + reason, now); return;
        }
        var steps = content.decode(task.getStepsJson());
        if (group.getNextStep() >= steps.size() || records.findStep(group.getId(), group.getNextStep()) != null) return;
        var row = intent(task, group, steps.get(group.getNextStep()), now);
        finish(row, FAILED, reason, now);
        advance(task, group, steps, now);
    }
    /** 回执不推进提交游标，仅在全部计划和原命令收敛后结束任务。 */
    public void completeIfSettled(ScriptMarketingTask task, long now) {
        if (task.getStatus() != RUNNING && task.getStatus() != PAUSED) return;
        int size = content.decode(task.getStepsJson()).size();
        if (groups.list(task.getId()).stream().allMatch(g -> g.getNextStep() >= size)
                && !records.hasPending(task.getId())) state(task, FINISHED, null, now);
    }
    /** 单群继续复核原账号，不能越过全任务暂停；单群暂停保留角色与已提交记录。 */
    public void groupAction(ScriptMarketingTask task, ScriptMarketingGroup group, String action, long now) {
        requireState(task, RUNNING, "请先继续整个任务，再操作单群");
        if ("pause".equals(action)) { pauseGroup(group, "业务人员暂停", now); return; }
        if (!"resume".equals(action)) throw new BusinessException(ErrorCode.VALIDATION, "未知群操作");
        requireBeforeEnd(task, now);
        var prepared = qualification.inspect(task.getAccountGroupId(), content.decode(task.getStepsJson()), List.of(group), true);
        if (!prepared.report().ready()) throw new ScriptQualificationException(prepared.report());
        releaseHeld(group.getId(), now);
        group.setPaused(false); group.setPauseReason(null);
        group.setNextAt(group.getNextStep() < content.decode(task.getStepsJson()).size()
                ? Math.max(now, task.getStartAt()) : Long.MAX_VALUE); groups.update(group);
    }
    private void start(ScriptMarketingTask task, long now) {
        if (task.getStatus() == RUNNING) return;
        requireState(task, DRAFT, "只能启动草稿任务"); requireBeforeEnd(task, now);
        var steps = content.decode(task.getStepsJson()); content.validateRoles(steps);
        var targets = groups.list(task.getId());
        var prepared = qualification.inspect(task.getAccountGroupId(), steps, targets, false);
        if (!prepared.report().ready()) throw new ScriptQualificationException(prepared.report());
        for (var group : targets) {
            group.setBindingsJson(content.encodeBindings(prepared.bindings().get(group.getGroupLinkId())));
            group.setPaused(false); group.setNextAt(Math.max(now, task.getStartAt())); groups.update(group);
        }
        state(task, RUNNING, null, now);
    }
    private void submit(ScriptMarketingTask task, ScriptMarketingGroup group, List<ScriptMarketingStepDTO> steps, long now) {
        if (records.findStep(group.getId(), group.getNextStep()) != null) return;
        var step = steps.get(group.getNextStep());
        var row = intent(task, group, step, now);
        MessageSendCommand command;
        try {
            var account = accounts.findOnlineProtocolRefs(List.of(row.getAccountId())).stream().findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "原绑定账号已离线或不可用"));
            command = new MessageSendCommand(account, new MessageSendCommand.MessageTarget(group.getGroupJid()),
                    content.payload(step), new MessageSendCommand.MessageCorrelation(task.getTenantId(), SOURCE,
                    null, null, null, null, null, null, row.getId()), row.getCommandId(),
                    MessageSendCommand.DEFAULT_SEND_INTERVAL_MS, 0L);
        } catch (BusinessException ex) {
            long failedAt = Math.max(now, System.currentTimeMillis());
            finish(row, FAILED, ex.getMessage(), failedAt); advance(task, group, steps, failedAt); return;
        }
        var response = sender.enqueue(List.of(command));
        var accepted = response.items().stream().filter(item -> row.getCommandId().equals(item.commandId())).findFirst();
        if (accepted.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT, "发送队列未返回原命令结果");
        long submittedAt = Math.max(now, System.currentTimeMillis());
        row.setSubmittedAt(submittedAt);
        row.setResultDeadlineAt(submittedAt + ScriptMarketingExecutionService.RESULT_TIMEOUT_MS);
        records.update(row);
        if (!accepted.get().accepted()) finish(row, FAILED,
                accepted.get().reasonCode() + ": " + accepted.get().reasonMessage(), submittedAt);
        // 意图、outbox 和下一次提交时间在同一事务持久化，不等待前项回执。
        advance(task, group, steps, submittedAt);
    }
    private ScriptMarketingSendRecord intent(ScriptMarketingTask task, ScriptMarketingGroup group,
            ScriptMarketingStepDTO step, long now) {
        Long accountId = content.decodeBindings(group.getBindingsJson()).get(step.roleKey());
        if (accountId == null) throw new BusinessException(ErrorCode.CONFLICT, "角色绑定缺失，任务无法继续");
        var row = new ScriptMarketingSendRecord();
        row.setTenantId(task.getTenantId()); row.setTaskId(task.getId()); row.setGroupId(group.getId());
        row.setStepIndex(group.getNextStep()); row.setAccountId(accountId);
        row.setCommandId("cmd_" + UUID.randomUUID().toString().replace("-", ""));
        row.setStatus(SENDING); row.setSubmittedAt(now); row.setResultDeadlineAt(now + ScriptMarketingExecutionService.RESULT_TIMEOUT_MS);
        records.insert(row); return row;
    }
    private void advance(ScriptMarketingTask task, ScriptMarketingGroup group, List<ScriptMarketingStepDTO> steps, long now) {
        int next = group.getNextStep() + 1; group.setNextStep(next);
        long nextAt = Long.MAX_VALUE;
        if (next < steps.size()) {
            var nextStep = steps.get(next);
            long wait = ThreadLocalRandom.current().nextLong(nextStep.waitMinSeconds(), (long) nextStep.waitMaxSeconds() + 1);
            nextAt = now + wait * 1000L;
        }
        group.setNextAt(nextAt); group.setRemainingWaitMs(0L); groups.update(group);
        completeIfSettled(task, now);
    }
    private void reconcile(ScriptMarketingTask task, Long groupId, long now) {
        for (var row : records.pendingGroup(groupId)) {
            if (row.getStatus() != SENDING
                    || row.getResultDeadlineAt() == null || row.getResultDeadlineAt() > now) continue;
            boolean unsent = control.expire(row.getCommandId());
            finish(row, unsent ? FAILED : UNKNOWN,
                    unsent ? "等待投递超时，已取消未发送命令" : "等待结果超时，结果未知，不自动重发", now);
        }
    }
    private void pause(ScriptMarketingTask task, long now) {
        if (task.getStatus() == PAUSED) return;
        requireState(task, RUNNING, "只有运行中的任务可以暂停");
        for (var group : groups.list(task.getId())) {
            hold(group.getId());
            group.setNextAt(Long.MAX_VALUE); group.setRemainingWaitMs(0L); groups.update(group);
        }
        state(task, PAUSED, "业务人员暂停", now);
    }
    private void pauseGroup(ScriptMarketingGroup group, String reason, long now) {
        hold(group.getId());
        group.setPaused(true); group.setPauseReason(reason);
        group.setNextAt(Long.MAX_VALUE); group.setRemainingWaitMs(0L); groups.update(group);
    }
    private void hold(Long groupId) {
        for (var row : records.pendingGroup(groupId)) {
            if (groupId.equals(row.getGroupId()) && row.getStatus() == SENDING && control.hold(row.getCommandId())) {
                row.setStatus(HELD); records.update(row);
            }
        }
    }
    private void resume(ScriptMarketingTask task, long now) {
        if (task.getStatus() == RUNNING) return;
        requireState(task, PAUSED, "只有暂停任务可以继续"); requireBeforeEnd(task, now);
        var targets = groups.list(task.getId());
        var prepared = qualification.inspect(task.getAccountGroupId(), content.decode(task.getStepsJson()), targets, true);
        if (!prepared.report().ready()) throw new ScriptQualificationException(prepared.report());
        for (var group : targets) {
            if (Boolean.TRUE.equals(group.getPaused())) continue;
            releaseHeld(group.getId(), now);
            group.setNextAt(group.getNextStep() < content.decode(task.getStepsJson()).size()
                    ? Math.max(now, task.getStartAt()) : Long.MAX_VALUE); group.setRemainingWaitMs(0L); groups.update(group);
        }
        state(task, RUNNING, null, now);
    }
    private void releaseHeld(Long groupId, long now) {
        for (var row : records.pendingGroup(groupId)) {
            if (!groupId.equals(row.getGroupId()) || row.getStatus() != HELD) continue;
            if (!control.resume(row.getCommandId())) throw new BusinessException(ErrorCode.CONFLICT, "原暂停命令状态已变化，请刷新后重试");
            row.setStatus(SENDING); row.setResultDeadlineAt(now + ScriptMarketingExecutionService.RESULT_TIMEOUT_MS); records.update(row);
        }
    }
    private void close(ScriptMarketingTask task, long now) {
        if (task.getStatus() == CLOSED || task.getStatus() == FINISHED) return;
        for (var row : records.pending(task.getId())) {
            boolean unsent = control.expire(row.getCommandId());
            finish(row, unsent ? FAILED : UNKNOWN,
                    unsent ? "任务已关闭，未发送命令已取消" : "任务已关闭，在途结果仍待确认", now);
        }
        for (var group : groups.list(task.getId())) { group.setNextAt(Long.MAX_VALUE); groups.update(group); }
        state(task, CLOSED, null, now);
    }
    private void finish(ScriptMarketingSendRecord row, int status, String reason, long now) {
        row.setStatus(status); row.setReason(reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
        row.setFinishedAt(now); records.update(row);
    }
    private void state(ScriptMarketingTask task, int status, String reason, long now) {
        task.setStatus(status); task.setPauseReason(reason == null ? null : reason.substring(0, Math.min(500, reason.length())));
        task.setUpdatedAt(now); tasks.updateState(task);
    }
    private void requireState(ScriptMarketingTask task, int state, String message) {
        if (!Objects.equals(task.getStatus(), state)) throw new BusinessException(ErrorCode.CONFLICT, message);
    }
    private void requireBeforeEnd(ScriptMarketingTask task, long now) {
        if (task.getEndAt() != null && task.getEndAt() <= now) throw new BusinessException(ErrorCode.CONFLICT, "任务已超过截止时间");
    }
}
