package com.armada.task.service;

import com.armada.task.mapper.JoinTaskAdminMapper;
import com.armada.task.mapper.JoinTaskApprovalMapper;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.model.dto.JoinTaskApprovalWork;
import com.armada.task.model.dto.JoinTaskResultReportedEvent;
import com.armada.task.model.entity.JoinTaskApproval;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.JoinTaskApprovalStage;
import com.armada.task.model.enums.JoinTaskDispatchState;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 短事务保存审核阶段与版本；恢复完成后复用现有进群成功/失败状态机。 */
@Service
public class JoinTaskApprovalTransactions {
    private static final long LEASE_MS = 120_000L;
    private static final long VERIFY_DELAY_MS = 5_000L;
    private final JoinTaskApprovalMapper approvals;
    private final JoinTaskAdminMapper results;
    private final JoinTaskMapper tasks;
    private final JoinTaskResultService completion;

    /** 按进群明细、恢复子记录的固定顺序持有行锁。 */
    public JoinTaskApprovalTransactions(JoinTaskApprovalMapper approvals, JoinTaskAdminMapper results,
            JoinTaskMapper tasks, JoinTaskResultService completion) {
        this.approvals = approvals; this.results = results; this.tasks = tasks; this.completion = completion;
    }

    /** 领取单步；已执行的批准/续进群在崩溃后只读核实，绝不重放。 */
    @Transactional(rollbackFor = Exception.class)
    public Optional<JoinTaskApprovalWork> claim(Long id, long now) {
        var result = results.lock(id);
        var row = approvals.lock(id);
        if (row == null || !JoinTaskApprovalStage.of(row.getStage()).pending()
                || row.getNextExecuteAt() == null || row.getNextExecuteAt() > now) return Optional.empty();
        var task = tasks.selectByTenantAndId(row.getJoinTaskId());
        if (result == null || !"PENDING".equals(result.getStatus()) || result.getDispatchState() != JoinTaskDispatchState.APPROVAL
                || task == null || !"RUNNING".equals(task.getStatus())) {
            finish(row, result, false, "任务已停止、删除或明细已失效，审核处理已停止", now);
            return Optional.empty();
        }
        if (now >= row.getDeadlineAt()) {
            finish(row, result, false, timeout(row), now);
            return Optional.empty();
        }
        var stage = JoinTaskApprovalStage.of(row.getStage());
        if (row.isInFlight() && stage == JoinTaskApprovalStage.CLOSE) {
            finish(row, result, false, "关闭群组审核结果未确认：执行超时或进程中断，后续已停止", now);
            return Optional.empty();
        }
        if (row.isInFlight() && (stage == JoinTaskApprovalStage.APPROVE || stage == JoinTaskApprovalStage.REJOIN)) {
            row.setStage(JoinTaskApprovalStage.VERIFY.code());
        }
        row.setInFlight(true); row.setVersion(row.getVersion() + 1);
        row.setNextExecuteAt(Math.min(now + LEASE_MS, row.getDeadlineAt()));
        save(row, now);
        return Optional.of(new JoinTaskApprovalWork(task, result, row));
    }

    /** 同版本且未过租约的结果才能推进；成功不得越过已停止任务。 */
    @Transactional(rollbackFor = Exception.class)
    public void succeeded(JoinTaskApprovalWork work, JoinTaskApprovalStage next, long now) {
        var result = results.lock(work.result().getId());
        var row = approvals.lock(work.result().getId());
        if (!matches(row, work)) return;
        var task = tasks.selectByTenantAndId(row.getJoinTaskId());
        if (task == null || !"RUNNING".equals(task.getStatus())) {
            finish(row, result, false, "任务已停止，后续已停止", now); return;
        }
        if (now >= row.getNextExecuteAt() || now >= row.getDeadlineAt()) {
            finish(row, result, false, timeout(row), now); return;
        }
        var observed = work.approval();
        row.setGroupJid(observed.getGroupJid()); row.setActorAccountId(observed.getActorAccountId());
        row.setActorPhone(observed.getActorPhone()); row.setTargetPhone(observed.getTargetPhone());
        row.setPendingJid(observed.getPendingJid());
        if (next == JoinTaskApprovalStage.SUCCESS) {
            finish(row, result, true, next.label(), now); return;
        }
        long delay = next.code() == row.getStage() ? VERIFY_DELAY_MS : 0L;
        row.setStage(next.code()); row.setReason(next.label()); row.setInFlight(false);
        row.setNextExecuteAt(Math.min(now + delay, row.getDeadlineAt())); save(row, now);
    }

    /** 已知失败立即结束，成员操作未知时只进入有界查询，不反复执行。 */
    @Transactional(rollbackFor = Exception.class)
    public void failed(JoinTaskApprovalWork work, String reason, boolean uncertain, long now) {
        var result = results.lock(work.result().getId());
        var row = approvals.lock(work.result().getId());
        if (!matches(row, work)) return;
        var stage = JoinTaskApprovalStage.of(row.getStage());
        if (uncertain && stage.code() >= JoinTaskApprovalStage.CHECK.code() && now < row.getDeadlineAt()) {
            row.setStage(stage == JoinTaskApprovalStage.CHECK ? stage.code() : JoinTaskApprovalStage.VERIFY.code());
            row.setReason("审核已关闭，进群结果待核实"); row.setInFlight(false);
            row.setNextExecuteAt(Math.min(now + VERIFY_DELAY_MS, row.getDeadlineAt())); save(row, now);
        } else finish(row, result, false, reason, now);
    }

    private void finish(JoinTaskApproval row, JoinTaskResult result, boolean success, String reason, long now) {
        row.setStage(success ? JoinTaskApprovalStage.SUCCESS.code() : JoinTaskApprovalStage.FAILED.code());
        row.setReason(reason); row.setInFlight(false); row.setNextExecuteAt(null); save(row, now);
        if (result == null || approvals.release(result) != 1) return;
        completion.apply(new JoinTaskResultReportedEvent("join-approval:" + row.getResultId() + ":" + row.getVersion(),
                row.getTenantId(), row.getJoinTaskId(), row.getResultId(), result.getAccountId(),
                row.getTargetProtocolAccountId(), result.getCommandId(), result.getAttemptNo(),
                success ? "JOINED" : "FAILED", row.getGroupJid(), success ? "" : "JOIN_APPROVAL_FAILED",
                reason, false, now, ""));
    }
    private static boolean matches(JoinTaskApproval row, JoinTaskApprovalWork work) {
        return row != null && row.isInFlight() && JoinTaskApprovalStage.of(row.getStage()).pending()
                && row.getVersion() == work.approval().getVersion();
    }
    private static String timeout(JoinTaskApproval row) {
        return row.getStage() <= JoinTaskApprovalStage.CLOSE.code()
                ? "关闭群组审核结果未确认：处理超时，后续已停止"
                : "审核已关闭，进群结果未确认：等待超时";
    }
    private void save(JoinTaskApproval row, long now) {
        row.setUpdatedAt(now);
        if (approvals.update(row) != 1) throw new IllegalStateException("审核处理记录已失效");
    }
}
