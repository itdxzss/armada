package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecutionMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.util.WhatsappJids;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 逐条推进单群料子进群阶段。
 *
 * <p>每次调用只向协议层提交一个料子；失败重试、下一条调度和任务暂停恢复都落在执行记录中，
 * 不使用线程睡眠，也不在协议调用期间持有数据库事务。</p>
 */
@Service
public class GroupPullMarketingMaterialEntryService {

    /** 安全日志记录器。 */
    private static final Logger log =
            LoggerFactory.getLogger(GroupPullMarketingMaterialEntryService.class);

    /** 暂停任务被并发派发后短暂延后，恢复接口会重新随机排期。 */
    private static final Duration PAUSED_RECHECK_DELAY = Duration.ofSeconds(15);

    /** 进群成功状态码。 */
    private static final int ENTRY_SUCCESS = 2;

    /** 进群失败状态码。 */
    private static final int ENTRY_FAILED = 3;

    /** 任务终止后待拉料子的统一失败原因。 */
    private static final String TASK_STOPPED_REASON = "任务已停止，未继续拉料";

    /** 拉群任务、执行和料子数据访问。 */
    private final GroupPullMarketingMapper mapper;

    /** 群成员操作协议端口。 */
    private final GroupParticipantPort participantPort;

    /** 单群执行结果结算器。 */
    private final GroupPullMarketingFinalizer finalizer;

    /** 逐料随机间隔策略。 */
    private final GroupPullMaterialEntryDelayPolicy delayPolicy;

    /** 短事务模板。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建逐料进群状态机。
     *
     * @param mapper 拉群任务、执行和料子数据访问
     * @param participantPort 群成员操作协议端口
     * @param finalizer 单群执行结果结算器
     * @param delayPolicy 逐料随机间隔策略
     * @param transactionManager 事务管理器
     */
    public GroupPullMarketingMaterialEntryService(
            GroupPullMarketingMapper mapper,
            GroupParticipantPort participantPort,
            GroupPullMarketingFinalizer finalizer,
            GroupPullMaterialEntryDelayPolicy delayPolicy,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.participantPort = participantPort;
        this.finalizer = finalizer;
        this.delayPolicy = delayPolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 尝试添加当前执行的下一条待进群料子。
     *
     * @param execution 当前第 5 阶段执行快照
     * @param builderAccount 建群账号协议引用
     */
    public void process(
            GroupPullMarketingExecution execution,
            ProtocolAccountRef builderAccount) {
        Optional<GroupPullMarketingTask> task = runnableTask(execution);
        if (task.isEmpty()) {
            return;
        }
        Optional<GroupPullMarketingExecutionMaterial> material = nextPendingMaterial(execution);
        if (material.isEmpty()) {
            advanceToPostMaterialStage(execution, System.currentTimeMillis());
            return;
        }
        EntryAttempt attempt = addOne(execution, builderAccount, material.get());
        if (attempt.groupBanned()) {
            mapper.markGroupBanned(execution.getId(), System.currentTimeMillis());
            finalizer.fail(execution.getId(), "添加料子失败：" + attempt.reason());
            log.warn(
                    "拉群营销逐料执行遇到封群 taskId={} executionId={} allocationNo={} attempt={}",
                    execution.getTaskId(),
                    execution.getId(),
                    material.get().getAllocationNo(),
                    attemptNumber(execution));
            return;
        }
        if (attempt.success()) {
            completeMaterial(execution, task.get(), material.get(), ENTRY_SUCCESS, null);
            return;
        }
        handleFailure(execution, task.get(), material.get(), attempt.reason());
    }

    /**
     * 建群账号离线时，把本次到期执行计为当前料子的一次失败尝试。
     *
     * @param execution 当前第 5 阶段执行快照
     */
    public void processBuilderUnavailable(GroupPullMarketingExecution execution) {
        Optional<GroupPullMarketingTask> task = runnableTask(execution);
        if (task.isEmpty()) {
            return;
        }
        Optional<GroupPullMarketingExecutionMaterial> material = nextPendingMaterial(execution);
        if (material.isEmpty()) {
            advanceToPostMaterialStage(execution, System.currentTimeMillis());
            return;
        }
        handleFailure(execution, task.get(), material.get(), "建群账号离线");
    }

    private Optional<GroupPullMarketingTask> runnableTask(
            GroupPullMarketingExecution execution) {
        long now = System.currentTimeMillis();
        MarketingTask runtime = mapper.selectTaskRuntime(execution.getTaskId());
        GroupPullMarketingTask extension = mapper.selectTaskById(execution.getTaskId());
        if (runtime == null || extension == null) {
            throw new IllegalStateException("拉群营销任务不存在 taskId=" + execution.getTaskId());
        }
        if (Integer.valueOf(MarketingTaskStatus.PAUSED.code()).equals(runtime.getStatus())) {
            delayPausedExecution(execution, now);
            return Optional.empty();
        }
        if (stopped(runtime, extension, now)) {
            stopPendingMaterials(execution, now);
            return Optional.empty();
        }
        return Optional.of(extension);
    }

    private void delayPausedExecution(GroupPullMarketingExecution execution, long now) {
        if (mapper.delayExecution(
                execution.getId(),
                execution.getExecutionStatus(),
                execution.getCurrentStage(),
                now + PAUSED_RECHECK_DELAY.toMillis(),
                null,
                now) != 1) {
            throw new IllegalStateException("暂停逐料执行时状态已变化");
        }
    }

    private void stopPendingMaterials(GroupPullMarketingExecution execution, long now) {
        transactionTemplate.executeWithoutResult(status -> {
            mapper.failPendingExecutionMaterials(execution.getId(), TASK_STOPPED_REASON, now);
            if (mapper.appendExecutionFailureReason(
                    execution.getId(), TASK_STOPPED_REASON, now) != 1) {
                throw new IllegalStateException("记录逐料停止原因时状态已变化");
            }
            advanceToPostMaterialStage(execution, now);
        });
        log.info(
                "拉群营销逐料执行已停止 taskId={} executionId={} result=TASK_STOPPED",
                execution.getTaskId(),
                execution.getId());
    }

    private Optional<GroupPullMarketingExecutionMaterial> nextPendingMaterial(
            GroupPullMarketingExecution execution) {
        return Optional.ofNullable(mapper.selectNextPendingExecutionMaterial(execution.getId()));
    }

    private EntryAttempt addOne(
            GroupPullMarketingExecution execution,
            ProtocolAccountRef builderAccount,
            GroupPullMarketingExecutionMaterial material) {
        String targetJid = WhatsappJids.userJid(material.getMaterialPhone());
        try {
            GroupParticipantBatchResult result = participantPort.updateParticipants(
                    builderAccount,
                    execution.getGroupJid(),
                    List.of(targetJid),
                    GroupParticipantAction.ADD);
            return participantResult(result, targetJid);
        } catch (ProtocolException exception) {
            return new EntryAttempt(
                    false,
                    GroupPullRetryPolicy.isGroupBanned(exception),
                    compactReason(exception));
        } catch (RuntimeException exception) {
            return new EntryAttempt(false, false, compactReason(exception));
        }
    }

    private EntryAttempt participantResult(GroupParticipantBatchResult result, String targetJid) {
        if (result == null || result.results() == null) {
            return new EntryAttempt(false, false, "协议未确认目标状态");
        }
        for (GroupParticipantBatchResult.Item item : result.results()) {
            if (item == null || !sameParticipant(targetJid, item.jid())) {
                continue;
            }
            if (GroupPullRetryPolicy.isGroupBanned(item)) {
                return new EntryAttempt(false, true, firstText(item.status(), item.rawStatus()));
            }
            if (GroupPullRetryPolicy.isParticipantSuccess(item)) {
                return new EntryAttempt(true, false, null);
            }
            return new EntryAttempt(false, false, firstText(item.rawStatus(), item.status()));
        }
        return new EntryAttempt(false, false, "协议未确认目标状态");
    }

    private void handleFailure(
            GroupPullMarketingExecution execution,
            GroupPullMarketingTask task,
            GroupPullMarketingExecutionMaterial material,
            String reason) {
        int retryCount = retryCount(execution);
        if (retryCount + 1 < GroupPullRetryPolicy.groupOperationAttempts()) {
            long nextExecuteAt = scheduleProgress(execution, task, retryCount + 1);
            logAttempt(execution, material, retryCount + 1, "RETRY", nextExecuteAt);
            return;
        }
        completeMaterial(execution, task, material, ENTRY_FAILED, reason);
    }

    private void completeMaterial(
            GroupPullMarketingExecution execution,
            GroupPullMarketingTask task,
            GroupPullMarketingExecutionMaterial material,
            int entryStatus,
            String reason) {
        long now = System.currentTimeMillis();
        int attempt = attemptNumber(execution);
        long[] nextExecuteAt = {now};
        transactionTemplate.executeWithoutResult(status -> {
            if (mapper.updateMaterialEntryResult(
                    material.getId(), entryStatus, reason, now) != 1) {
                throw new IllegalStateException("保存料子进群结果时状态已变化");
            }
            if (mapper.countPendingExecutionMaterials(execution.getId()) == 0) {
                advanceToPostMaterialStage(execution, now);
                return;
            }
            nextExecuteAt[0] = scheduleProgress(execution, task, 0, now);
        });
        logAttempt(
                execution,
                material,
                attempt,
                entryStatus == ENTRY_SUCCESS ? "SUCCESS" : "FAILED",
                nextExecuteAt[0]);
    }

    private long scheduleProgress(
            GroupPullMarketingExecution execution,
            GroupPullMarketingTask task,
            int nextRetryCount) {
        return scheduleProgress(execution, task, nextRetryCount, System.currentTimeMillis());
    }

    private long scheduleProgress(
            GroupPullMarketingExecution execution,
            GroupPullMarketingTask task,
            int nextRetryCount,
            long now) {
        long nextExecuteAt = delayPolicy.nextExecuteAt(
                now, task.getMaterialEntryIntervalSeconds());
        GroupPullMarketingMapper.MaterialStageProgress progress =
                new GroupPullMarketingMapper.MaterialStageProgress(
                        execution.getId(),
                        execution.getExecutionStatus(),
                        execution.getCurrentStage(),
                        retryCount(execution),
                        nextRetryCount,
                        nextExecuteAt,
                        null,
                        now);
        if (mapper.updateMaterialStageProgress(progress) != 1) {
            throw new IllegalStateException("保存逐料重试进度时状态已变化");
        }
        execution.setStageRetryCount(nextRetryCount);
        execution.setNextExecuteAt(nextExecuteAt);
        return nextExecuteAt;
    }

    private void advanceToPostMaterialStage(
            GroupPullMarketingExecution execution,
            long now) {
        if (mapper.advanceExecutionStage(
                execution.getId(),
                execution.getExecutionStatus(),
                execution.getCurrentStage(),
                GroupPullExecutionStage.SET_MARKETER_ADMIN.code(),
                GroupPullExecutionStatus.EXECUTING.code(),
                now,
                now) != 1) {
            throw new IllegalStateException("推进逐料后续阶段时状态已变化");
        }
        execution.setCurrentStage(GroupPullExecutionStage.SET_MARKETER_ADMIN.code());
        execution.setStageRetryCount(0);
        execution.setNextExecuteAt(now);
    }

    private void logAttempt(
            GroupPullMarketingExecution execution,
            GroupPullMarketingExecutionMaterial material,
            int attempt,
            String result,
            long nextExecuteAt) {
        log.info(
                "拉群营销逐料执行 taskId={} executionId={} allocationNo={} attempt={} result={} nextExecuteAt={}",
                execution.getTaskId(),
                execution.getId(),
                material.getAllocationNo(),
                attempt,
                result,
                nextExecuteAt);
    }

    private static boolean stopped(
            MarketingTask task,
            GroupPullMarketingTask extension,
            long now) {
        return !Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())
                || task.getTaskEndAt() == null
                || task.getTaskEndAt() <= now
                || !Integer.valueOf(GroupPullResourceStatus.LOCKED.code())
                        .equals(extension.getResourceStatus());
    }

    private static int retryCount(GroupPullMarketingExecution execution) {
        return execution.getStageRetryCount() == null ? 0 : execution.getStageRetryCount();
    }

    private static int attemptNumber(GroupPullMarketingExecution execution) {
        return retryCount(execution) + 1;
    }

    private static boolean sameParticipant(String expectedJid, String actualJid) {
        if (!StringUtils.hasText(actualJid)) {
            return false;
        }
        String expectedPhone = phoneOf(expectedJid);
        return expectedPhone.equals(phoneOf(actualJid));
    }

    private static String phoneOf(String value) {
        String normalized = value == null ? "" : value.trim();
        int separator = normalized.indexOf('@');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private static String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : "协议未确认目标状态";
    }

    private static String compactReason(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (!StringUtils.hasText(message)) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    private record EntryAttempt(boolean success, boolean groupBanned, String reason) {
    }
}
