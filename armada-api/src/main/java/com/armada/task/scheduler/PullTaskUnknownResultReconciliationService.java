package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.task.model.dto.PullTaskFactStatusCriteria;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 通过实时群成员事实收敛 SUBMITTED/UNKNOWN，绝不重新提交可能已生效的协议命令。
 */
@Service
public class PullTaskUnknownResultReconciliationService {

    private static final String UNCONFIRMED = "PROTOCOL_RESULT_UNCONFIRMED";
    private static final String NOT_IN_GROUP = "MEMBER_NOT_IN_GROUP_CONFIRMED";
    private static final String NOT_IN_GROUP_MESSAGE = "成员快照确认账号不在群内";
    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
    private static final List<Integer> MEMBER_OBSERVABLE_ACTIONS = List.of(
            PullTaskAccountActionType.INVITE_TO_GROUP.code(),
            PullTaskAccountActionType.JOIN_BY_LINK.code(),
            PullTaskAccountActionType.PROMOTE_MANAGER.code());
    private static final List<Integer> CALL_OPEN = List.of(
            PullTaskPullCallStatus.SUBMITTED.code(), PullTaskPullCallStatus.UNKNOWN.code());
    private static final List<Integer> PULL_OPEN = List.of(
            PullTaskMaterialPullStatus.SUBMITTED.code(), PullTaskMaterialPullStatus.UNKNOWN.code());
    private static final List<Integer> MEMBERSHIP_OPEN = List.of(
            PullTaskGroupAccountMembershipStatus.JOINING.code(),
            PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
    private static final List<Integer> ADMIN_OPEN = List.of(
            PullTaskMaterialAdminStatus.SUBMITTED.code(),
            PullTaskMaterialAdminStatus.UNKNOWN.code());

    private final PullTaskUnknownResultResources resources;
    private final AccountProtocolLookupService accountLookup;
    private final PullTaskMemberQueryService memberQueryService;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskPullCallReconciliationService pullCallReconciliationService;
    private final PullTaskPullWaveProgressService waveProgress;
    private final GroupExecutionAccountSelector groupAccountSelector;

    /** 构造未知结果收敛服务。 */
    public PullTaskUnknownResultReconciliationService(
            PullTaskUnknownResultResources resources,
            AccountProtocolLookupService accountLookup,
            PullTaskMemberQueryService memberQueryService,
            PullTaskUnknownResultCoordination coordination) {
        this.resources = resources;
        this.accountLookup = accountLookup;
        this.memberQueryService = memberQueryService;
        this.executionMapper = coordination.executionMapper();
        this.pullCallReconciliationService = coordination.pullCalls();
        this.waveProgress = coordination.waveProgress();
        this.groupAccountSelector = coordination.groupAccounts();
    }

    /**
     * 收敛一条执行行；查询失败只把超时 SUBMITTED 标为 UNKNOWN，不推断失败。
     */
    public PullTaskUnknownResultReconciliationStats reconcile(
            PullTaskGroupExecution execution, long submittedCutoff, long now) {
        return reconcile(execution, submittedCutoff, submittedCutoff, now);
    }

    /** 使用独立的通用动作与批量逐成员超时边界收敛执行行。 */
    public PullTaskUnknownResultReconciliationStats reconcile(
            PullTaskGroupExecution execution,
            long submittedCutoff,
            long participantCutoff,
            long now) {
        List<PullTaskGroupAccount> accounts = accounts(execution.getId());
        List<PullTaskMaterialMember> materials = resources.materialMapper()
                .selectByExecution(execution.getId());
        List<PullTaskPullCall> calls = resources.callMapper()
                .selectByExecution(execution.getId());
        List<PullTaskAccountAction> actions = resources.actionMapper()
                .selectByExecutionAndStatuses(execution.getId(), ACTION_OPEN);
        Map<Long, List<PullTaskPullCallMemberAttempt>> attemptsByCall = new LinkedHashMap<>();
        for (PullTaskPullCall call : calls) {
            attemptsByCall.put(call.getId(), resources.attemptMapper().selectByCall(call.getId()));
        }
        boolean legacySnapshotRequired = actions.stream().anyMatch(action ->
                MEMBER_OBSERVABLE_ACTIONS.contains(action.getActionType()))
                || hasOpenAdminFacts(materials, accounts)
                || calls.stream().anyMatch(call -> CALL_OPEN.contains(call.getCallStatus())
                && attemptsByCall.getOrDefault(call.getId(), List.of()).isEmpty());
        MemberSnapshot snapshot = legacySnapshotRequired
                ? queryMembers(
                execution, accounts, materials,
                reconciliationBusinessKey(actions, accounts, materials, calls), now)
                : MemberSnapshot.unavailable();
        if (snapshot.pending()) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        Counter counter = new Counter();
        ReconciliationContext context = new ReconciliationContext(
                snapshot, submittedCutoff, now, counter);
        reconcileActions(actions, accounts, context);
        reconcileCalls(
                execution, calls, materials, accounts, attemptsByCall,
                participantCutoff, context);
        reconcileAdmins(materials, accounts, context);
        return counter.snapshot();
    }

    private void reconcileActions(
            List<PullTaskAccountAction> actions,
            List<PullTaskGroupAccount> accounts,
            ReconciliationContext context) {
        Map<Long, PullTaskGroupAccount> byId = new LinkedHashMap<>();
        accounts.forEach(row -> byId.put(row.getId(), row));
        for (PullTaskAccountAction action : actions) {
            PullTaskGroupAccount target = byId.get(action.getTargetGroupAccountId());
            boolean promotion = Objects.equals(
                    action.getActionType(), PullTaskAccountActionType.PROMOTE_MANAGER.code());
            boolean membershipAction = Objects.equals(
                    action.getActionType(), PullTaskAccountActionType.INVITE_TO_GROUP.code())
                    || Objects.equals(
                    action.getActionType(), PullTaskAccountActionType.JOIN_BY_LINK.code());
            GroupParticipantResult member = (promotion || membershipAction) && target != null
                    ? context.snapshot().member(target.getAccountPhone()) : null;
            boolean effectObserved = promotion ? hasAdmin(member) : membershipAction && member != null;
            if (effectObserved) {
                int changed = promotion
                        ? resources.actionMapper().transitionManagerAdminObservation(
                        action.getId(), ACTION_OPEN, PullTaskActionStatus.SUCCESS.code(),
                        false, null, null, context.now())
                        : resources.actionMapper().transitionResult(transition(
                        action.getId(), ACTION_OPEN, PullTaskActionStatus.SUCCESS.code(),
                        PullTaskFactResult.success(member.jid(), context.now()), context.now()));
                context.counter().confirm(changed);
                if (membershipAction) {
                    confirmMembership(target, member.jid(), context.now());
                }
            } else if (membershipAction
                    && target != null
                    && userJid(target.getAccountPhone()) != null
                    && context.snapshot().queried()
                    && Objects.equals(
                    action.getActionStatus(), PullTaskActionStatus.UNKNOWN.code())) {
                int changed = resources.actionMapper().transitionResult(transition(
                        action.getId(), List.of(PullTaskActionStatus.UNKNOWN.code()),
                        PullTaskActionStatus.FAILED.code(),
                        PullTaskFactResult.reason(NOT_IN_GROUP, NOT_IN_GROUP_MESSAGE),
                        context.now()));
                context.counter().confirm(changed);
                resources.accountMapper().transitionMembership(transition(
                        target.getId(), List.of(
                                PullTaskGroupAccountMembershipStatus.UNKNOWN.code()),
                        PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code(),
                        PullTaskFactResult.reason(NOT_IN_GROUP, NOT_IN_GROUP_MESSAGE),
                        context.now()));
            } else if (staleSubmitted(
                    action.getActionStatus(), action.getSubmittedAt(), context.cutoff(),
                    PullTaskActionStatus.SUBMITTED.code())) {
                int changed = promotion
                        ? resources.actionMapper().transitionManagerAdminObservation(
                        action.getId(), List.of(PullTaskActionStatus.SUBMITTED.code()),
                        PullTaskActionStatus.UNKNOWN.code(), true,
                        PullTaskExecutionReasonCode.MANAGER_ADMIN_UNCONFIRMED.name(),
                        PullTaskExecutionReasonCode.MANAGER_ADMIN_UNCONFIRMED.message(),
                        context.now())
                        : resources.actionMapper().transitionResult(transition(
                        action.getId(), List.of(PullTaskActionStatus.SUBMITTED.code()),
                        PullTaskActionStatus.UNKNOWN.code(), PullTaskFactResult.reason(
                                UNCONFIRMED, "协议动作结果待查询或回调确认"), context.now()));
                context.counter().unknown(changed);
                markMembershipUnknown(target, membershipAction, context.now());
            }
        }
    }

    private void reconcileCalls(
            PullTaskGroupExecution execution,
            List<PullTaskPullCall> calls,
            List<PullTaskMaterialMember> materials,
            List<PullTaskGroupAccount> accounts,
            Map<Long, List<PullTaskPullCallMemberAttempt>> attemptsByCall,
            long participantCutoff,
            ReconciliationContext context) {
        for (PullTaskPullCall call : calls) {
            if (call.getCallStatus() == null || !CALL_OPEN.contains(call.getCallStatus())) {
                continue;
            }
            List<PullTaskPullCallMemberAttempt> attempts = attemptsByCall
                    .getOrDefault(call.getId(), List.of());
            if (!attempts.isEmpty()) {
                context.counter().add(pullCallReconciliationService.reconcile(
                        execution, call, attempts, accounts,
                        participantCutoff, context.now()));
                continue;
            }
            boolean stale = staleSubmitted(
                    call.getCallStatus(), call.getSubmittedAt(), context.cutoff(),
                    PullTaskPullCallStatus.SUBMITTED.code());
            reconcileMaterials(call, materials, stale, context);
            reconcileStations(call, accounts, stale, context);
            boolean unresolved = unresolved(call.getId());
            if (!unresolved) {
                int changed = resources.callMapper().transitionResult(transition(
                        call.getId(), CALL_OPEN, PullTaskPullCallStatus.WRITTEN_BACK.code(),
                        PullTaskFactResult.empty(), context.now()));
                context.counter().confirm(changed);
                wakeAfterSubmittedCall(execution, call, changed, context.now());
            } else if (stale) {
                int changed = resources.callMapper().transitionResult(transition(
                        call.getId(), List.of(PullTaskPullCallStatus.SUBMITTED.code()),
                        PullTaskPullCallStatus.UNKNOWN.code(), PullTaskFactResult.reason(
                                UNCONFIRMED, "批量拉人存在待查询或回调确认的结果"),
                        context.now()));
                context.counter().unknown(changed);
                wakeAfterSubmittedCall(execution, call, changed, context.now());
            }
        }
    }

    private void wakeAfterSubmittedCall(
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            int callChanged,
            long now) {
        if (callChanged != 1
                || !Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.SUBMITTED.code())
                || !Objects.equals(execution.getExecutionStatus(),
                PullTaskExecutionStatus.EXECUTING.code())
                || !Objects.equals(execution.getStage(), PullTaskExecutionStage.PULL_EXECUTION.code())
                || execution.getVersion() == null) {
            return;
        }
        if (call.getPullWaveId() != null) {
            waveProgress.wakeCollecting(
                    execution.getTenantId(), execution.getId(), call.getPullWaveId(), now);
            return;
        }
        executionMapper.transitionProtocolResult(new PullTaskExecutionResultTransition(
                execution.getId(), execution.getTaskId(), execution.getVersion(),
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStage.PULL_EXECUTION.code(),
                PullTaskExecutionStage.PULL_EXECUTION.code(), null, now, now));
    }

    private void reconcileMaterials(
            PullTaskPullCall call,
            List<PullTaskMaterialMember> materials,
            boolean stale,
            ReconciliationContext context) {
        for (PullTaskMaterialMember material : materials) {
            if (!Objects.equals(material.getPullCallId(), call.getId())
                    || material.getPullStatus() == null
                    || !PULL_OPEN.contains(material.getPullStatus())) {
                continue;
            }
            GroupParticipantResult member = context.snapshot()
                    .member(material.getNormalizedPhone());
            if (member != null) {
                context.counter().confirm(resources.materialMapper().transitionPullResult(transition(
                        material.getId(), PULL_OPEN, PullTaskMaterialPullStatus.SUCCESS.code(),
                        PullTaskFactResult.success(member.jid(), context.now()), context.now())));
            } else if (stale && Objects.equals(material.getPullStatus(),
                    PullTaskMaterialPullStatus.SUBMITTED.code())) {
                context.counter().unknown(resources.materialMapper().transitionPullResult(transition(
                        material.getId(), List.of(PullTaskMaterialPullStatus.SUBMITTED.code()),
                        PullTaskMaterialPullStatus.UNKNOWN.code(), PullTaskFactResult.reason(
                                UNCONFIRMED, "成员入群结果待查询或回调确认"), context.now())));
            }
        }
    }

    private void reconcileStations(
            PullTaskPullCall call,
            List<PullTaskGroupAccount> accounts,
            boolean stale,
            ReconciliationContext context) {
        for (PullTaskGroupAccount account : accounts) {
            if (!Objects.equals(account.getPullCallId(), call.getId())
                    || account.getMembershipStatus() == null
                    || !MEMBERSHIP_OPEN.contains(account.getMembershipStatus())) {
                continue;
            }
            GroupParticipantResult member = context.snapshot().member(account.getAccountPhone());
            if (member != null) {
                context.counter().confirm(transitionMembership(
                        account.getId(), MEMBERSHIP_OPEN,
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code(),
                        context.now(), context.now()));
            } else if (stale && Objects.equals(account.getMembershipStatus(),
                    PullTaskGroupAccountMembershipStatus.JOINING.code())) {
                context.counter().unknown(resources.accountMapper().transitionMembership(
                        transition(account.getId(), List.of(
                                        PullTaskGroupAccountMembershipStatus.JOINING.code()),
                                PullTaskGroupAccountMembershipStatus.UNKNOWN.code(),
                                PullTaskFactResult.reason(
                                        UNCONFIRMED,
                                        "成员进群结果待查询或回调确认"),
                                context.now())));
            }
        }
    }

    private void reconcileAdmins(
            List<PullTaskMaterialMember> materials,
            List<PullTaskGroupAccount> accounts,
            ReconciliationContext context) {
        for (PullTaskMaterialMember material : materials) {
            if (material.getAdminStatus() == null
                    || !ADMIN_OPEN.contains(material.getAdminStatus())) {
                continue;
            }
            GroupParticipantResult member = context.snapshot().member(
                    material.getWaJid() == null ? material.getNormalizedPhone() : material.getWaJid());
            if (hasAdmin(member)) {
                context.counter().confirm(resources.materialMapper().transitionAdminResult(transition(
                        material.getId(), ADMIN_OPEN, PullTaskMaterialAdminStatus.SUCCESS.code(),
                        PullTaskFactResult.success(member.jid(), context.now()), context.now())));
            } else if (staleUpdated(
                    material.getAdminStatus(), material.getUpdatedAt(), context.cutoff(),
                    PullTaskMaterialAdminStatus.SUBMITTED.code())) {
                context.counter().unknown(resources.materialMapper().transitionAdminResult(transition(
                        material.getId(), List.of(PullTaskMaterialAdminStatus.SUBMITTED.code()),
                        PullTaskMaterialAdminStatus.UNKNOWN.code(),
                        PullTaskFactResult.reason(UNCONFIRMED, null), context.now())));
            }
        }
        reconcileAccountAdmins(accounts, context);
    }

    private static boolean hasOpenAdminFacts(
            List<PullTaskMaterialMember> materials,
            List<PullTaskGroupAccount> accounts) {
        boolean materialOpen = materials.stream().anyMatch(row ->
                row.getAdminStatus() != null && ADMIN_OPEN.contains(row.getAdminStatus()));
        if (materialOpen) {
            return true;
        }
        return accounts.stream().anyMatch(row -> row.getAdminStatus() != null
                && (Objects.equals(row.getAdminStatus(),
                PullTaskGroupAccountAdminStatus.SUBMITTED.code())
                || Objects.equals(row.getAdminStatus(),
                PullTaskGroupAccountAdminStatus.UNKNOWN.code())));
    }

    private void reconcileAccountAdmins(
            List<PullTaskGroupAccount> accounts,
            ReconciliationContext context) {
        List<Integer> open = List.of(PullTaskGroupAccountAdminStatus.SUBMITTED.code(),
                PullTaskGroupAccountAdminStatus.UNKNOWN.code());
        for (PullTaskGroupAccount account : accounts) {
            if (account.getAdminStatus() == null || !open.contains(account.getAdminStatus())) {
                continue;
            }
            if (hasAdmin(context.snapshot().member(account.getAccountPhone()))) {
                context.counter().confirm(resources.accountMapper().transitionAdminStatus(
                        account.getId(), open,
                        PullTaskGroupAccountAdminStatus.SUCCESS.code(), context.now()));
            } else if (staleUpdated(
                    account.getAdminStatus(), account.getUpdatedAt(), context.cutoff(),
                    PullTaskGroupAccountAdminStatus.SUBMITTED.code())) {
                context.counter().unknown(resources.accountMapper().transitionAdminStatus(
                        account.getId(), List.of(
                                PullTaskGroupAccountAdminStatus.SUBMITTED.code()),
                        PullTaskGroupAccountAdminStatus.UNKNOWN.code(), context.now()));
            }
        }
    }

    private boolean unresolved(long callId) {
        PullTaskFactStatusCriteria pull = new PullTaskFactStatusCriteria(callId, PULL_OPEN);
        PullTaskFactStatusCriteria station = new PullTaskFactStatusCriteria(
                callId, MEMBERSHIP_OPEN);
        return resources.materialMapper().countByPullCallAndStatuses(pull) > 0
                || resources.accountMapper()
                .countByPullCallAndMembershipStatuses(station) > 0;
    }

    private MemberSnapshot queryMembers(
            PullTaskGroupExecution execution,
            List<PullTaskGroupAccount> accounts,
            List<PullTaskMaterialMember> materials,
            String businessKey,
            long now) {
        if (execution.getGroupJid() == null || execution.getGroupJid().isBlank()) {
            return MemberSnapshot.unavailable();
        }
        List<Long> confirmed = accounts.stream()
                .filter(row -> Objects.equals(row.getMembershipStatus(),
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()))
                .map(PullTaskGroupAccount::getAccountId).distinct().toList();
        List<Long> accountIds = new ArrayList<>(confirmed);
        accounts.stream().map(PullTaskGroupAccount::getAccountId)
                .filter(Objects::nonNull)
                .filter(accountId -> !accountIds.contains(accountId))
                .forEach(accountIds::add);
        Map<Long, ProtocolAccountRef> refsByAccountId = new LinkedHashMap<>();
        for (GroupExecutionAccount candidate
                : groupAccountSelector.findCandidates(execution.getGroupLinkId())) {
            ProtocolAccountRef ref = candidate.protocolRef();
            refsByAccountId.putIfAbsent(ref.armadaAccountId(), ref);
        }
        for (ProtocolAccountRef ref : accountLookup.findOnlineProtocolRefs(accountIds)) {
            refsByAccountId.putIfAbsent(ref.armadaAccountId(), ref);
        }
        List<ProtocolAccountRef> refs = List.copyOf(refsByAccountId.values());
        if (refs.isEmpty()) {
            return MemberSnapshot.unavailable();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        accounts.stream().map(PullTaskGroupAccount::getAccountPhone)
                .map(PullTaskUnknownResultReconciliationService::userJid)
                .filter(Objects::nonNull).forEach(targets::add);
        materials.stream().map(row -> row.getWaJid() == null
                        ? row.getNormalizedPhone() : row.getWaJid())
                .map(PullTaskUnknownResultReconciliationService::userJid)
                .filter(Objects::nonNull).forEach(targets::add);
        if (targets.isEmpty()) {
            return MemberSnapshot.unavailable();
        }
        for (ProtocolAccountRef ref : refs) {
            PullTaskMemberQueryResult result = memberQueryService.requestOrReadOnce(
                    new PullTaskMemberQueryRequest(
                            execution.getTaskId(), execution.getId(),
                            actorBusinessKey(businessKey, ref),
                            PullTaskMemberQueryPurpose.UNKNOWN_RESULT_RECONCILIATION,
                            ref, execution.getGroupJid(), List.copyOf(targets)), now);
            if (result.state() == PullTaskMemberQueryResult.State.PENDING) {
                return MemberSnapshot.waiting();
            }
            if (result.state() == PullTaskMemberQueryResult.State.AVAILABLE) {
                return MemberSnapshot.availableFacts(result.members());
            }
        }
        return MemberSnapshot.unavailable();
    }

    private static String actorBusinessKey(String businessKey, ProtocolAccountRef actor) {
        String identity = actor.armadaAccountId() + ":" + actor.backend().name()
                + ":" + actor.protocolAccountId();
        return businessKey + ":actor:"
                + java.util.UUID.nameUUIDFromBytes(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String reconciliationBusinessKey(
            List<PullTaskAccountAction> actions,
            List<PullTaskGroupAccount> accounts,
            List<PullTaskMaterialMember> materials,
            List<PullTaskPullCall> calls) {
        StringBuilder state = new StringBuilder();
        actions.stream().sorted(java.util.Comparator.comparing(PullTaskAccountAction::getId))
                .forEach(row -> state.append("a:").append(row.getId()).append(':')
                        .append(row.getActionStatus()).append(':').append(row.getUpdatedAt()).append(';'));
        accounts.stream().sorted(java.util.Comparator.comparing(PullTaskGroupAccount::getId))
                .forEach(row -> state.append("g:").append(row.getId()).append(':')
                        .append(row.getMembershipStatus()).append(':').append(row.getAdminStatus())
                        .append(':').append(row.getUpdatedAt()).append(';'));
        materials.stream().sorted(java.util.Comparator.comparing(PullTaskMaterialMember::getId))
                .forEach(row -> state.append("m:").append(row.getId()).append(':')
                        .append(row.getPullStatus()).append(':').append(row.getAdminStatus())
                        .append(':').append(row.getUpdatedAt()).append(';'));
        calls.stream().sorted(java.util.Comparator.comparing(PullTaskPullCall::getId))
                .forEach(row -> state.append("c:").append(row.getId()).append(':')
                        .append(row.getCallStatus()).append(':').append(row.getUpdatedAt()).append(';'));
        return "unknown-reconciliation:"
                + java.util.UUID.nameUUIDFromBytes(
                state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String userJid(String identity) {
        try {
            return identity == null || identity.isBlank() ? null : WhatsappJids.userJid(identity);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<PullTaskGroupAccount> accounts(long executionId) {
        List<PullTaskGroupAccount> rows = new ArrayList<>();
        for (PullTaskGroupAccountRole role : PullTaskGroupAccountRole.values()) {
            rows.addAll(resources.accountMapper()
                    .selectByExecutionAndRole(executionId, role.code()));
        }
        return rows;
    }

    private void confirmMembership(
            PullTaskGroupAccount target, String jid, long now) {
        if (target != null) {
            transitionMembership(target.getId(), MEMBERSHIP_OPEN,
                    PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), now, now);
        }
    }

    private void markMembershipUnknown(
            PullTaskGroupAccount target, boolean membershipAction, long now) {
        if (membershipAction && target != null) {
            resources.accountMapper().transitionMembership(transition(
                    target.getId(), List.of(
                            PullTaskGroupAccountMembershipStatus.JOINING.code()),
                    PullTaskGroupAccountMembershipStatus.UNKNOWN.code(),
                    PullTaskFactResult.reason(
                            UNCONFIRMED,
                            "成员进群结果待查询或回调确认"),
                    now));
        }
    }

    private int transitionMembership(
            long id, List<Integer> expected, int target, Long joinedAt, long now) {
        return resources.accountMapper().transitionMembership(transition(
                id, expected, target,
                joinedAt == null ? PullTaskFactResult.empty()
                        : PullTaskFactResult.success(null, joinedAt), now));
    }

    private static PullTaskFactTransition transition(
            long id,
            List<Integer> expected,
            int target,
            PullTaskFactResult result,
            long now) {
        return new PullTaskFactTransition(id, expected, target, result, now);
    }

    private static boolean staleSubmitted(
            Integer current, Long submittedAt, long cutoff, int submittedStatus) {
        return Objects.equals(current, submittedStatus)
                && submittedAt != null && submittedAt <= cutoff;
    }

    private static boolean staleUpdated(
            Integer current, Long updatedAt, long cutoff, int submittedStatus) {
        return Objects.equals(current, submittedStatus)
                && updatedAt != null && updatedAt <= cutoff;
    }

    private static boolean hasAdmin(GroupParticipantResult member) {
        return member != null && (Boolean.TRUE.equals(member.admin())
                || Boolean.TRUE.equals(member.owner()));
    }

    private static String phone(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        int at = normalized.indexOf('@');
        normalized = at < 0 ? normalized : normalized.substring(0, at);
        int device = normalized.indexOf(':');
        normalized = device < 0 ? normalized : normalized.substring(0, device);
        return normalized.replaceAll("[^0-9]", "");
    }

    private record ReconciliationContext(
            MemberSnapshot snapshot,
            long cutoff,
            long now,
            Counter counter) {
    }

    private record MemberSnapshot(
            boolean queried,
            boolean pending,
            Map<String, GroupParticipantResult> members) {

        private static MemberSnapshot unavailable() {
            return new MemberSnapshot(false, false, Map.of());
        }

        private static MemberSnapshot waiting() {
            return new MemberSnapshot(false, true, Map.of());
        }

        private static MemberSnapshot availableFacts(List<PullTaskMemberFact> source) {
            Map<String, GroupParticipantResult> members = new LinkedHashMap<>();
            if (source != null) {
                source.stream().filter(Objects::nonNull).filter(PullTaskMemberFact::inGroup)
                        .forEach(fact -> {
                            String jid = fact.participantJid() == null
                                    ? fact.targetJid() : fact.participantJid();
                            String memberPhone = fact.phoneNumber() == null
                                    ? fact.targetJid() : fact.phoneNumber();
                            members.putIfAbsent(phone(fact.targetJid()), new GroupParticipantResult(
                                    jid, memberPhone, fact.admin(), false,
                                    fact.admin() ? "admin" : null));
                        });
            }
            return new MemberSnapshot(true, false, Map.copyOf(members));
        }

        private GroupParticipantResult member(String identity) {
            return queried ? members.get(phone(identity)) : null;
        }
    }

    private static final class Counter {
        private int confirmed;
        private int markedUnknown;

        private void confirm(int changed) {
            confirmed += changed;
        }

        private void unknown(int changed) {
            markedUnknown += changed;
        }

        private void add(PullTaskUnknownResultReconciliationStats stats) {
            confirmed += stats.confirmed();
            markedUnknown += stats.markedUnknown();
        }

        private PullTaskUnknownResultReconciliationStats snapshot() {
            return new PullTaskUnknownResultReconciliationStats(confirmed, markedUnknown);
        }
    }
}
