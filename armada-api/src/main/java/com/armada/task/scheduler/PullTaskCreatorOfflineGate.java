package com.armada.task.scheduler;

import com.armada.account.model.AccountCreatorReservation;
import com.armada.account.model.AccountRoleAvailability;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.model.PullTaskOfflineRoleWaitPolicy;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 新群固定建群人的连接闸门，供建群与提权阶段共用；不重新选择建群人。 */
@Service
public class PullTaskCreatorOfflineGate {
    private static final Logger log = LoggerFactory.getLogger(PullTaskCreatorOfflineGate.class);
    private final PullTaskGroupAccountMapper roles;
    private final AccountProtocolLookupService accounts;
    private final AccountOnlineCommandService online;
    private final PullTaskOfflineRoleWaitProperties properties;
    private final TransactionTemplate recoveries;
    private final PullTaskGroupExecutionFailureService failures;

    /**
     * @param roles 固定角色持久化入口
     * @param accounts 账号连接可用性入口
     * @param online 预留建群人的专属恢复入口
     * @param properties 任务侧等待配置
     * @param transactionManager 提交后恢复使用的新事务管理器
     * @param failures 群执行行终止服务
     */
    public PullTaskCreatorOfflineGate(PullTaskGroupAccountMapper roles,
            AccountProtocolLookupService accounts, AccountOnlineCommandService online,
            PullTaskOfflineRoleWaitProperties properties, PlatformTransactionManager transactionManager,
            PullTaskGroupExecutionFailureService failures) {
        this.roles = roles;
        this.accounts = accounts;
        this.online = online;
        this.properties = properties;
        this.failures = failures;
        this.recoveries = new TransactionTemplate(transactionManager);
        this.recoveries.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return 任务侧是否启用离线等待；调用方关闭时保留原分支 */
    public boolean isEnabled() { return properties.isEnabled(); }

    /**
     * 判定已冻结的 PROMOTER 首槽；仅精确属于此执行行的 RESERVED 离线账号允许申请恢复。
     * @param candidate 当前执行行
     * @param now 当前时间，单位毫秒
     * @return 在线身份、等待截止时间与恢复意图，或不可恢复结果
     */
    public Result evaluate(PullTaskGroupExecution candidate, long now) {
        List<PullTaskGroupAccount> creators = roles.selectByExecutionAndRole(
                candidate.getId(), PullTaskGroupAccountRole.PROMOTER.code());
        Long accountId = creators.isEmpty() ? null : creators.get(0).getAccountId();
        AccountRoleAvailability availability = accountId == null ? null
                : accounts.findRoleAvailability(List.of(accountId)).get(accountId);
        var decision = PullTaskOfflineRoleWaitPolicy.decide(availability, properties.getCreatorGraceMs(), now);
        if (decision.kind() == PullTaskOfflineRoleWaitPolicy.Kind.USE) {
            ProtocolAccountRef ref = accounts.findActiveProtocolRef(accountId).orElse(null);
            return new Result(ref == null ? Kind.GIVE_UP : Kind.READY, ref, null, accountId, false);
        }
        if (decision.kind() == PullTaskOfflineRoleWaitPolicy.Kind.WAIT) {
            return new Result(Kind.WAIT, null, decision.waitUntil(), accountId,
                    ownedReservation(candidate, availability));
        }
        return new Result(Kind.GIVE_UP, null, null, accountId, false);
    }

    /**
     * 仅在等待状态成功持久化后调用；恢复失败不会回滚已提交的等待状态。
     * @param candidate 已成功 defer 的执行行，包含唯一任务和租户归属
     * @param result 当前闸门判定
     */
    public void requestRecoveryAfterCommit(PullTaskGroupExecution candidate, Result result) {
        if (!isEnabled() || result.kind() != Kind.WAIT || !result.requestReservedReonline()) {
            return;
        }
        long tenantId = candidate.getTenantId();
        long taskId = candidate.getTaskId();
        long executionId = candidate.getId();
        long accountId = result.accountId();
        Runnable recovery = () -> recover(tenantId, taskId, executionId, accountId);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { recovery.run(); }
            });
        } else {
            recovery.run();
        }
    }

    /**
     * 终止不可替换的建群人所属执行行，调用者必须立即返回终止结果。
     * @param candidate 当前执行行
     * @param now 当前时间，单位毫秒
     * @return FAILED，终止写入冲突通过异常让外层事务回滚
     */
    public PullTaskExecutionDispatchResult terminate(PullTaskGroupExecution candidate, long now) {
        failures.terminate(candidate.getTenantId(), candidate.getId(),
                PullTaskExecutionReasonCode.GROUP_CREATOR_OFFLINE, now);
        return PullTaskExecutionDispatchResult.FAILED;
    }

    private static boolean ownedReservation(PullTaskGroupExecution candidate, AccountRoleAvailability account) {
        AccountCreatorReservation reservation = account.reservation();
        return reservation != null && "RESERVED".equals(reservation.lifecycle())
                && Objects.equals(reservation.tenantId(), candidate.getTenantId())
                && Objects.equals(reservation.taskId(), candidate.getTaskId())
                && Objects.equals(reservation.groupExecutionId(), candidate.getId())
                && account.offline();
    }

    private void recover(long tenantId, long taskId, long executionId, long accountId) {
        Long previous = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            recoveries.executeWithoutResult(status -> online.reonlineReservedCreator(accountId, taskId, executionId));
        } catch (Exception failure) {
            log.warn("预留建群人提交后恢复失败 tenantId={} taskId={} executionId={} accountId={}",
                    tenantId, taskId, executionId, accountId, failure);
        } finally {
            if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
        }
    }

    /** 固定建群人的三个门控结果。 */
    public enum Kind { READY, WAIT, GIVE_UP }

    /**
     * @param kind 闸门结果
     * @param ref READY 时可用的协议身份
     * @param waitUntil WAIT 时的离线截止时间
     * @param accountId 已冻结的建群人账号
     * @param requestReservedReonline 是否在等待提交后申请专属恢复
     */
    public record Result(Kind kind, ProtocolAccountRef ref, Long waitUntil,
                         Long accountId, boolean requestReservedReonline) { }
}
