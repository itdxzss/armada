package com.armada.account.service;

import com.armada.account.converter.AccountTierCheckConverter;
import com.armada.account.mapper.AccountTierCheckMapper;
import com.armada.account.model.dto.AccountTierCheckCandidate;
import com.armada.account.model.dto.AccountTierCheckClaim;
import com.armada.account.model.dto.AccountTierCheckWork;
import com.armada.account.model.entity.AccountTierCheckItem;
import com.armada.account.model.entity.AccountTierCheckTask;
import com.armada.account.model.enums.AccountTierCheckState;
import com.armada.admin.service.CurrentIdentityService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import com.armada.shared.security.AuthPrincipal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 短事务认领/结算；账号行锁跨批次互斥，HTTP 永远不在此类执行。 */
@Service
public class AccountTierCheckTransactions {
    private final AccountTierCheckMapper mapper;
    private final AccountTierCheckConverter converter;
    private final AccountTierCheckProperties config;
    private final CurrentIdentityService identities;
    /** 注入持久层、字段映射、限流配置及实时权限服务。 */
    public AccountTierCheckTransactions(AccountTierCheckMapper mapper, AccountTierCheckConverter converter,
            AccountTierCheckProperties config, CurrentIdentityService identities) {
        this.mapper = mapper; this.converter = converter; this.config = config; this.identities = identities;
    }
    /** 先锁批次，再锁执行账号；READ_COMMITTED 保证等待跨批次账号锁后读取到新在途事实。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Optional<AccountTierCheckClaim> claim(AccountTierCheckWork work, long now) {
        var task = mapper.lockTask(work.taskId());
        if (task == null || task.getStatus() != AccountTierCheckState.RUNNING) return Optional.empty();
        var item = mapper.item(work.itemId());
        if (!matches(item, work) || item.getStatus() != AccountTierCheckState.PENDING || item.getNextExecuteAt() > now)
            return Optional.empty();
        var identity = identities.load(task.getCreatedBy(), task.getTenantId());
        var target = mapper.account(item.getAccountId());
        String reason = targetFailure(identity, target, item);
        if (!reason.isEmpty()) { failPending(task, item, reason, now); return Optional.empty(); }
        var candidates = executorCandidates(task, item, identity.orElseThrow());
        var executor = selectExecutor(candidates, now);
        if (executor.isEmpty()) {
            // 有在线探针但处于在途/冷却时只是等待；全部离线才记失败。
            if (!candidates.isEmpty()) {
                mapper.defer(item.getId(), nextAttemptAt(candidates, now), now); return Optional.empty();
            }
            failPending(task, item, task.getMode() == AccountTierCheckState.SELF ? "EXECUTOR_OFFLINE" : "NO_PROBE_ONLINE", now);
            return Optional.empty();
        }
        var actor = executor.get();
        // 锁定查询必须 flushCache，避免 MyBatis 一级缓存复用锁前的 executorBlocked/account 结果。
        mapper.lockAccount(actor.id());
        mapper.lockCredential(actor.id());
        actor = mapper.account(actor.id());
        if (!eligibleExecutor(actor, identity.orElseThrow()) || mapper.executorBlocked(actor.id(), now) > 0) {
            mapper.defer(item.getId(), now + config.getIntervalMs(), now); return Optional.empty();
        }
        var last = mapper.latestExecutor(actor.id());
        int count = last == null || last.getExecutorRoundCount() >= config.getRoundSize() ? 0 : last.getExecutorRoundCount();
        item.setExecutorAccountId(actor.id()); item.setExecutorRoundCount(count + 1);
        item.setNextExecuteAt(now + config.getResultTimeoutMs()); item.setUpdatedAt(now);
        if (mapper.claim(item) != 1) return Optional.empty();
        return Optional.of(new AccountTierCheckClaim(work, item.getAttemptNo() + 1,
                new ProtocolAccountRef(actor.id(), ProtocolBackend.valueOf(actor.protocolBackend()), actor.protocolAccountId(), actor.wsPhone()),
                AccountTierCheckPolicy.identity(actor), item.getPhone()));
    }
    /** 只结算当前尝试；校验目标和发起方协议句柄/凭据版本，旧身份结果不写档位。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void apply(AccountTierCheckClaim claim, ProtocolAccountIntegrity result, long now) {
        var task = mapper.lockTask(claim.work().taskId());
        if (task == null) return;
        var item = mapper.item(claim.work().itemId());
        if (!matches(item, claim.work()) || item.getStatus() != AccountTierCheckState.QUERYING
                || item.getAttemptNo() != claim.attemptNo() || !Objects.equals(item.getExecutorAccountId(), claim.executor().armadaAccountId())) return;
        // 目标和发起方可能不同；稳定锁顺序避免两个批次互查时形成反向锁环。
        for (long id : List.of(item.getAccountId(), item.getExecutorAccountId()).stream().distinct().sorted().toList()) {
            mapper.lockAccount(id); mapper.lockCredential(id);
        }
        var identity = identities.load(task.getCreatedBy(), task.getTenantId());
        String reason = targetFailure(identity, mapper.account(item.getAccountId()), item);
        var executor = mapper.account(item.getExecutorAccountId());
        if (reason.isEmpty() && (executor == null || !AccountTierCheckPolicy.owns(identity.orElseThrow(), executor)
                || !AccountTierCheckPolicy.identity(executor).equals(claim.executorIdentity()))) reason = "EXECUTOR_IDENTITY_CHANGED";
        var effective = reason.isEmpty() ? result : ProtocolAccountIntegrity.failure(reason);
        finishFields(item, effective, now);
        mapper.finish(item);
        refresh(task, now);
    }
    /** 进程重启后的超时认领或探针离线回收；不会自动重查已成功/失败的记录。 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void recover(AccountTierCheckWork work, long now) {
        var task = mapper.lockTask(work.taskId());
        if (task == null) return;
        var item = mapper.item(work.itemId());
        if (!matches(item, work) || item.getStatus() != AccountTierCheckState.QUERYING) return;
        boolean offline = task.getMode() == AccountTierCheckState.PROBE && !AccountTierCheckPolicy.online(mapper.account(item.getExecutorAccountId()));
        if (!offline && item.getNextExecuteAt() > now) return;
        mapper.release(item, task.getStatus() == AccountTierCheckState.STOPPED ? AccountTierCheckState.CANCELED : AccountTierCheckState.PENDING, now);
        refresh(task, now);
    }
    private List<AccountTierCheckCandidate> executorCandidates(AccountTierCheckTask task, AccountTierCheckItem item, AuthPrincipal p) {
        var ids = task.getMode() == AccountTierCheckState.SELF ? List.of(item.getAccountId()) : AccountTierCheckPolicy.probes(task.getProbeAccountIds());
        return ids.stream().map(mapper::account).filter(a -> eligibleExecutor(a, p)).toList();
    }
    private Optional<AccountTierCheckCandidate> selectExecutor(List<AccountTierCheckCandidate> candidates, long now) {
        return candidates.stream().filter(a -> mapper.executorBlocked(a.id(), now) == 0)
                .min(Comparator.comparingLong((AccountTierCheckCandidate a) -> mapper.executorLoad(a.id()))
                        .thenComparingLong(a -> {
                            var last = mapper.latestExecutor(a.id());
                            return last == null ? 0 : last.getNextExecuteAt();
                        }).thenComparingLong(AccountTierCheckCandidate::id));
    }
    private long nextAttemptAt(List<AccountTierCheckCandidate> candidates, long now) {
        long next = now + config.getIntervalMs();
        for (var candidate : candidates) {
            var last = mapper.latestExecutor(candidate.id());
            if (last != null && last.getNextExecuteAt() > now) next = Math.min(next, last.getNextExecuteAt());
        }
        return next;
    }
    private static boolean eligibleExecutor(AccountTierCheckCandidate a, AuthPrincipal p) {
        return AccountTierCheckPolicy.owns(p, a) && AccountTierCheckPolicy.online(a)
                && "WEB".equals(a.protocolBackend()) && a.protocolAccountId() != null && !a.protocolAccountId().isBlank()
                && a.wsPhone() != null && !a.wsPhone().isBlank();
    }
    private static boolean matches(AccountTierCheckItem item, AccountTierCheckWork work) {
        return item != null && Objects.equals(item.getTaskId(), work.taskId()) && Objects.equals(item.getTenantId(), work.tenantId());
    }
    private static String targetFailure(Optional<AuthPrincipal> identity, AccountTierCheckCandidate target, AccountTierCheckItem item) {
        if (identity.isEmpty() || !identity.get().permissions().contains("tenant:account:edit")) return "ACCESS_REVOKED";
        if (!AccountTierCheckPolicy.owns(identity.get(), target)) return "ACCOUNT_ACCESS_CHANGED";
        if (!Objects.equals(AccountTierCheckPolicy.identity(target), item.getTargetIdentity())) return "ACCOUNT_IDENTITY_CHANGED";
        return "";
    }
    private void failPending(AccountTierCheckTask task, AccountTierCheckItem item, String reason, long now) {
        finishFields(item, ProtocolAccountIntegrity.failure(reason), now);
        mapper.failPending(item);
        refresh(task, now);
    }
    private void finishFields(AccountTierCheckItem item, ProtocolAccountIntegrity result, long now) {
        boolean success = result.status() == ProtocolAccountIntegrity.Status.OK;
        int status = success ? AccountTierCheckState.SUCCESS : result.status() == ProtocolAccountIntegrity.Status.NOT_ALLOWED
                ? AccountTierCheckState.UNQUERYABLE : AccountTierCheckState.FAILED;
        item.setStatus(status); item.setReasonCode(success ? null : result.reasonCode());
        if (success) converter.result(result, item);
        var previous = mapper.previousSuccess(item.getAccountId(), item.getId());
        if (previous != null) {
            item.setPrevItemId(previous.getId()); item.setPrevTrustTier(previous.getTrustTier()); item.setPrevCheckedAt(previous.getCheckedAt());
        }
        item.setTierChange(previous != null && !success ? AccountTierCheckPolicy.LOST
                : AccountTierCheckPolicy.change(item.getPrevTrustTier(), item.getTrustTier(), success));
        boolean cooldown = result.status() == ProtocolAccountIntegrity.Status.EMPTY || item.getExecutorRoundCount() >= config.getRoundSize();
        if (cooldown) item.setExecutorRoundCount(config.getRoundSize());
        item.setNextExecuteAt(now + (cooldown ? config.getCooldownMs() : config.getIntervalMs()));
        item.setCheckedAt(now); item.setUpdatedAt(now);
    }
    private void refresh(AccountTierCheckTask task, long now) {
        if (task.getStatus() == AccountTierCheckState.STOPPED) return;
        var stats = mapper.stats(task.getId());
        int status = stats.pending() > 0 ? AccountTierCheckState.RUNNING : stats.success() == stats.total()
                ? AccountTierCheckState.COMPLETED : AccountTierCheckState.PARTIAL;
        mapper.setTaskStatus(task.getId(), status, now);
    }
}
