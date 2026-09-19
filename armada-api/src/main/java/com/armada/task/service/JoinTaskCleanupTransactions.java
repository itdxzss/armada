package com.armada.task.service;

import com.armada.group.service.WhatsappGroupBusinessDepartureService;
import com.armada.task.mapper.JoinTaskAdminMapper;
import com.armada.task.mapper.JoinTaskCleanupMapper;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.JoinTaskCleanupContext;
import com.armada.task.model.dto.JoinTaskCleanupWork;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskCleanup;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.JoinTaskCleanupStatus;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 短事务保存清理意图和单项结果，外部调用期间不持数据库锁。 */
@Service
public class JoinTaskCleanupTransactions {
    private static final long CALL_DEADLINE_MS = 120_000L;
    private final JoinTaskCleanupMapper cleanups;
    private final JoinTaskAdminMapper admins;
    private final JoinTaskMapper tasks;
    private final JoinTaskResultMapper results;
    private final WhatsappGroupBusinessDepartureService departures;
    private final JoinTaskIntervalPolicy intervals = new JoinTaskIntervalPolicy();

    /** 复用任务汇总与群域离群事实写入。 */
    public JoinTaskCleanupTransactions(JoinTaskCleanupMapper cleanups, JoinTaskAdminMapper admins,
            JoinTaskMapper tasks, JoinTaskResultMapper results, WhatsappGroupBusinessDepartureService departures) {
        this.cleanups = cleanups; this.admins = admins; this.tasks = tasks;
        this.results = results; this.departures = departures;
    }

    /** 管理员阶段结束后，按开关进入清理或沿用原有下一条进群排期。 */
    @Transactional(rollbackFor = Exception.class)
    public void completeAdminStage(JoinTask task, JoinTaskResult result, boolean success, long now) {
        if (success && task.isClearAdminsAndLeaveEnabled()) {
            enqueue(task, result, now);
            tasks.refreshCounters(task.getId());
        } else {
            advance(task, result, now);
        }
    }

    /** 提权成功后原子登记清理阶段，原号缺失立即失败，不任意选择账号退出。 */
    @Transactional(rollbackFor = Exception.class)
    public void enqueue(JoinTask task, JoinTaskResult result, long now) {
        var cleanup = new JoinTaskCleanup();
        cleanup.setResultId(result.getId()); cleanup.setJoinTaskId(task.getId());
        cleanup.setTenantId(result.getTenantId()); cleanup.setGroupJid(result.getGroupJid());
        cleanup.setContextJson(""); cleanup.setReason(""); cleanup.setUpdatedAt(now);
        boolean invalid = result.getAdminActorAccountId() == null
                || result.getAdminActorAccountId().equals(result.getAccountId());
        cleanup.setStatus((invalid ? JoinTaskCleanupStatus.FAILED : JoinTaskCleanupStatus.WAITING).code());
        cleanup.setNextExecuteAt(invalid ? null : now);
        if (invalid) cleanup.setReason("准备清理失败：缺少有效的原执行账号");
        if (cleanups.insert(cleanup) != 1) throw new IllegalStateException("清理阶段登记失败");
        if (invalid) advance(task, result, now);
    }

    /** 领取下一步；过期在途请求停止，不能重发或退群。 */
    @Transactional(rollbackFor = Exception.class)
    public Optional<JoinTaskCleanupWork> claim(Long id, long now) {
        var result = admins.lock(id);
        var cleanup = cleanups.lock(id);
        if (result == null || cleanup == null || cleanup.getNextExecuteAt() == null
                || cleanup.getNextExecuteAt() > now) return Optional.empty();
        var task = tasks.selectByTenantAndId(result.getJoinTaskId());
        var status = JoinTaskCleanupStatus.of(cleanup.getStatus());
        if (!status.pending()) return Optional.empty();
        if (task == null || !"RUNNING".equals(task.getStatus()) || !task.isClearAdminsAndLeaveEnabled()) {
            fail(cleanup, "任务已停止、删除或关闭清理，后续不再执行", now);
            return Optional.empty();
        }
        if (status.inFlight()) {
            fail(cleanup, "执行超时或进程中断，结果未确认", now);
            advance(task, result, now);
            return Optional.empty();
        }
        var next = switch (status) {
            case WAITING -> JoinTaskCleanupStatus.LISTING;
            case REMOVE_READY -> JoinTaskCleanupStatus.REMOVING;
            case LEAVE_READY -> JoinTaskCleanupStatus.LEAVING;
            default -> throw new IllegalStateException("清理阶段不可派发");
        };
        cleanup.setStatus(next.code()); cleanup.setActiveGroupJid(cleanup.getGroupJid());
        cleanup.setNextExecuteAt(now + CALL_DEADLINE_MS); cleanup.setUpdatedAt(now);
        // 数据库唯一键保证同租户同群不会同时清理；冲突由调度器下轮重试领取。
        save(cleanup);
        return Optional.of(new JoinTaskCleanupWork(cleanup, result));
    }

    /** 当前在途操作明确成功后才推进一个阶段，重复或过期结果不推进。 */
    @Transactional(rollbackFor = Exception.class)
    public void succeeded(JoinTaskCleanupWork work, JoinTaskCleanupContext context, long now) {
        var result = admins.lock(work.result().getId());
        var cleanup = cleanups.lock(work.result().getId());
        if (!matches(cleanup, work)) return;
        if (cleanup.getNextExecuteAt() <= now) {
            fail(cleanup, "执行超时，迟到回执不再推进后续操作", now);
            advance(tasks.selectByTenantAndId(result.getJoinTaskId()), result, now);
            return;
        }
        var status = JoinTaskCleanupStatus.of(cleanup.getStatus());
        var previous = status == JoinTaskCleanupStatus.LISTING ? context
                : JoinTaskCleanupContext.parse(cleanup.getContextJson());
        String operationId = "join-cleanup:" + cleanup.getResultId() + ":" + previous.completed();
        if (status == JoinTaskCleanupStatus.REMOVING) {
            var target = previous.targets().get(previous.completed());
            departures.recordConfirmedRemovals(cleanup.getTenantId(), cleanup.getGroupJid(),
                    Collections.singletonMap(target.jid(), target.phone()), now, operationId);
        } else if (status == JoinTaskCleanupStatus.LEAVING && !previous.originalAlreadyAbsent()) {
            departures.recordConfirmedLeave(cleanup.getTenantId(), cleanup.getGroupJid(),
                    previous.originalPhone(), now, operationId);
        }
        var next = status == JoinTaskCleanupStatus.LEAVING ? JoinTaskCleanupStatus.SUCCESS
                : context.completed() < context.targets().size() ? JoinTaskCleanupStatus.REMOVE_READY
                : JoinTaskCleanupStatus.LEAVE_READY;
        cleanup.setContextJson(context.toJson()); cleanup.setStatus(next.code()); cleanup.setUpdatedAt(now);
        cleanup.setNextExecuteAt(next == JoinTaskCleanupStatus.SUCCESS ? null : now);
        if (next == JoinTaskCleanupStatus.SUCCESS) cleanup.setActiveGroupJid(null);
        save(cleanup);
        if (next == JoinTaskCleanupStatus.SUCCESS) advance(tasks.selectByTenantAndId(result.getJoinTaskId()), result, now);
    }

    /** 失败只终止该条清理链，保持已经成功的进群、提权及成员移除事实。 */
    @Transactional(rollbackFor = Exception.class)
    public void failed(JoinTaskCleanupWork work, String reason, long now) {
        var result = admins.lock(work.result().getId());
        var cleanup = cleanups.lock(work.result().getId());
        if (!matches(cleanup, work)) return;
        fail(cleanup, reason, now);
        advance(tasks.selectByTenantAndId(result.getJoinTaskId()), result, now);
    }

    private boolean matches(JoinTaskCleanup cleanup, JoinTaskCleanupWork work) {
        return cleanup != null && cleanup.getStatus() == work.cleanup().getStatus()
                && Objects.equals(cleanup.getNextExecuteAt(), work.cleanup().getNextExecuteAt())
                && JoinTaskCleanupStatus.of(cleanup.getStatus()).inFlight();
    }
    private void fail(JoinTaskCleanup cleanup, String reason, long now) {
        String target = "";
        if (cleanup.getStatus() == JoinTaskCleanupStatus.REMOVING.code()) {
            var context = JoinTaskCleanupContext.parse(cleanup.getContextJson());
            target = " 目标=" + context.targets().get(context.completed()).jid();
        }
        String message = JoinTaskCleanupStatus.of(cleanup.getStatus()).name() + target + "：" + reason;
        cleanup.setReason(message.substring(0, Math.min(255, message.length())));
        cleanup.setStatus(JoinTaskCleanupStatus.FAILED.code()); cleanup.setNextExecuteAt(null);
        cleanup.setActiveGroupJid(null); cleanup.setUpdatedAt(now); save(cleanup);
    }
    private void advance(JoinTask task, JoinTaskResult result, long now) {
        if (task == null) return;
        results.activateNextPending(task.getId(), result.getAccountId(), result.getId(), intervals.nextExecuteAt(task, now), now);
        tasks.refreshCounters(task.getId()); tasks.markDoneWhenNoPending(task.getId(), now);
    }
    private void save(JoinTaskCleanup cleanup) {
        if (cleanups.update(cleanup) != 1) throw new IllegalStateException("清理阶段写入失败");
    }
}
