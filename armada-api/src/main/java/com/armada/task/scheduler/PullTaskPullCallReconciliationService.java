package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
import com.armada.task.model.dto.PullTaskUncertainParticipantSettlement;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskPullCallRosterCheckStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskRosterObservation;
import com.armada.task.service.impl.PullTaskPullCallParticipantResultService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 新版逐号码批次在结果窗口结束后的一次名单核实。 */
@Service
public class PullTaskPullCallReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(
            PullTaskPullCallReconciliationService.class);
    private static final String UNCONFIRMED = "PROTOCOL_RESULT_UNCONFIRMED";

    private final PullTaskUnknownResultResources resources;
    private final AccountProtocolLookupService accountLookup;
    private final PullTaskMemberQueryService memberQueryService;
    private final PullTaskPullCallParticipantResultService participantResultService;

    /** 创建逐号码异常批次核实服务。 */
    public PullTaskPullCallReconciliationService(
            PullTaskUnknownResultResources resources,
            AccountProtocolLookupService accountLookup,
            PullTaskMemberQueryService memberQueryService,
            PullTaskPullCallParticipantResultService participantResultService) {
        this.resources = resources;
        this.accountLookup = accountLookup;
        this.memberQueryService = memberQueryService;
        this.participantResultService = participantResultService;
    }

    /**
     * 核实一个已确认使用新版 attempt 台账的批次；外部名单读取至多一次。
     */
    public PullTaskUnknownResultReconciliationStats reconcile(
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            List<PullTaskPullCallMemberAttempt> attempts,
            List<PullTaskGroupAccount> accounts,
            long cutoff,
            long now) {
        if (!Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.SUBMITTED.code())
                || call.getSubmittedAt() == null) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        List<PullTaskPullCallMemberAttempt> unresolved = attempts.stream()
                .filter(row -> Objects.equals(
                        row.getLifecycleStatus(), PullTaskParticipantAttemptStatus.SUBMITTED.code()))
                .filter(row -> row.getExecutionState()
                        != PullTaskParticipantExecutionState.NOT_STARTED)
                .toList();
        if (unresolved.isEmpty()) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        if (call.getSubmittedAt() > cutoff
                && !unavailablePullerStillOwnsOpenAttempt(call, unresolved, accounts)) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        RosterDecision decision = rosterDecision(call, cutoff, now);
        if (!decision.proceed()) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        persistMissingAsUncertain(unresolved, now);
        MemberSnapshot snapshot = decision.query()
                ? queryMembers(execution, accounts, call, unresolved, now)
                : MemberSnapshot.failed(PullTaskPullCallRosterCheckStatus.FAILED);
        if (snapshot == null) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        int confirmed = 0;
        int released = 0;
        for (PullTaskPullCallMemberAttempt attempt : unresolved) {
            PullTaskRosterObservation observation = observation(snapshot, attempt);
            PullTaskUncertainParticipantSettlement settlement =
                    new PullTaskUncertainParticipantSettlement(
                            new PullTaskUncertainParticipantSettlement.Context(
                                    execution.getTenantId(), call, execution),
                            attempt, observation, now);
            if (participantResultService.settleUncertain(settlement)) {
                if (observation == PullTaskRosterObservation.PRESENT) {
                    confirmed++;
                } else {
                    released++;
                }
            }
        }
        if (decision.finish()) {
            if (resources.callMapper().finishRosterCheck(
                    call.getId(), PullTaskPullCallRosterCheckStatus.CLAIMED.code(),
                    snapshot.status().code(), now) != 1) {
                throw new IllegalStateException("异常批次名单核实完成状态写入失败");
            }
            log.info("event=pull_call_roster_finished tenantId={} taskId={} "
                            + "executionId={} waveId={} callId={} rosterStatus={} "
                            + "unresolvedCount={} confirmedCount={} releasedCount={}",
                    execution.getTenantId(), execution.getTaskId(), execution.getId(),
                    call.getPullWaveId(), call.getId(), snapshot.status(),
                    unresolved.size(), confirmed, released);
        }
        return new PullTaskUnknownResultReconciliationStats(confirmed, released);
    }

    private static boolean unavailablePullerStillOwnsOpenAttempt(
            PullTaskPullCall call,
            List<PullTaskPullCallMemberAttempt> unresolved,
            List<PullTaskGroupAccount> accounts) {
        Long pullerId = call.getPullerGroupAccountId();
        if (pullerId == null || accounts.stream().noneMatch(account ->
                Objects.equals(account.getId(), pullerId)
                        && account.getAvailabilityStatus() != null
                        && !Objects.equals(account.getAvailabilityStatus(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code()))) {
            return false;
        }
        return unresolved.stream().anyMatch(attempt ->
                Objects.equals(attempt.getPullerGroupAccountId(), pullerId));
    }

    private static PullTaskRosterObservation observation(
            MemberSnapshot snapshot,
            PullTaskPullCallMemberAttempt attempt) {
        return switch (snapshot.status()) {
            case SUCCEEDED -> snapshot.member(attempt.getTargetJid()) == null
                    ? PullTaskRosterObservation.ABSENT
                    : PullTaskRosterObservation.PRESENT;
            case FAILED, SKIPPED -> PullTaskRosterObservation.UNAVAILABLE;
            default -> throw new IllegalStateException("名单核实状态尚未完成");
        };
    }

    private void persistMissingAsUncertain(
            List<PullTaskPullCallMemberAttempt> unresolved,
            long now) {
        for (PullTaskPullCallMemberAttempt attempt : unresolved) {
            if (attempt.getProtocolOutcome() != null) {
                continue;
            }
            PullTaskParticipantAttemptTransition transition =
                    new PullTaskParticipantAttemptTransition(
                            new PullTaskParticipantAttemptTransition.Scope(attempt.getId(), now),
                            new PullTaskParticipantAttemptTransition.Expected(List.of(
                                    PullTaskParticipantAttemptStatus.SUBMITTED.code())),
                            new PullTaskParticipantAttemptTransition.Target(
                                    PullTaskParticipantAttemptStatus.SUBMITTED.code(),
                                    PullTaskBatchParticipantProtocolOutcome.UNKNOWN.name(),
                                    PullTaskParticipantExecutionState.UNCERTAIN, null),
                            PullTaskFactResult.reason(
                                    UNCONFIRMED, "结果收集窗口结束仍未收到逐号码回调"));
            if (resources.attemptMapper().transition(transition) != 1) {
                throw new IllegalStateException("缺失逐号码回调转为待核实状态失败");
            }
            attempt.setProtocolOutcome(PullTaskBatchParticipantProtocolOutcome.UNKNOWN.name());
            attempt.setExecutionState(PullTaskParticipantExecutionState.UNCERTAIN);
        }
    }

    private RosterDecision rosterDecision(
            PullTaskPullCall call,
            long cutoff,
            long now) {
        int status = call.getRosterCheckStatus() == null
                ? PullTaskPullCallRosterCheckStatus.NOT_STARTED.code()
                : call.getRosterCheckStatus();
        if (status == PullTaskPullCallRosterCheckStatus.NOT_STARTED.code()) {
            int claimed = resources.callMapper().claimRosterCheck(
                    call.getId(), PullTaskPullCallRosterCheckStatus.NOT_STARTED.code(),
                    PullTaskPullCallRosterCheckStatus.CLAIMED.code(), now);
            return claimed == 1
                    ? new RosterDecision(true, true, true)
                    : RosterDecision.stop();
        }
        if (status == PullTaskPullCallRosterCheckStatus.CLAIMED.code()) {
            return new RosterDecision(true, true, true);
        }
        return new RosterDecision(true, false, false);
    }

    private MemberSnapshot queryMembers(
            PullTaskGroupExecution execution,
            List<PullTaskGroupAccount> accounts,
            PullTaskPullCall call,
            List<PullTaskPullCallMemberAttempt> unresolved,
            long now) {
        if (execution.getGroupJid() == null || execution.getGroupJid().isBlank()) {
            return MemberSnapshot.failed(PullTaskPullCallRosterCheckStatus.SKIPPED);
        }
        List<Long> accountIds = accounts.stream()
                .filter(row -> Objects.equals(row.getMembershipStatus(),
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()))
                .map(PullTaskGroupAccount::getAccountId)
                .filter(Objects::nonNull).distinct().toList();
        if (accountIds.isEmpty()) {
            return MemberSnapshot.failed(PullTaskPullCallRosterCheckStatus.SKIPPED);
        }
        List<ProtocolAccountRef> refs = accountLookup.findActiveProtocolRefs(accountIds);
        if (refs == null || refs.isEmpty()) {
            return MemberSnapshot.failed(PullTaskPullCallRosterCheckStatus.SKIPPED);
        }
        List<String> targetJids = unresolved.stream()
                .map(PullTaskPullCallMemberAttempt::getTargetJid)
                .filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList();
        if (targetJids.isEmpty()) {
            return MemberSnapshot.failed(PullTaskPullCallRosterCheckStatus.SKIPPED);
        }
        PullTaskMemberQueryResult result = memberQueryService.requestOrRead(
                new PullTaskMemberQueryRequest(
                        execution.getTaskId(), execution.getId(),
                        "pull-call-reconciliation:" + call.getId() + ":" + call.getSubmittedAt(),
                        PullTaskMemberQueryPurpose.PULL_CALL_RECONCILIATION,
                        refs.get(0), execution.getGroupJid(), targetJids), now);
        return switch (result.state()) {
            case PENDING -> null;
            case FAILED -> MemberSnapshot.failed(PullTaskPullCallRosterCheckStatus.FAILED);
            case AVAILABLE -> MemberSnapshot.succeededFacts(result.members());
        };
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

    private record RosterDecision(boolean proceed, boolean query, boolean finish) {

        private static RosterDecision stop() {
            return new RosterDecision(false, false, false);
        }
    }

    private record MemberSnapshot(
            PullTaskPullCallRosterCheckStatus status,
            Map<String, GroupParticipantResult> members) {

        private static MemberSnapshot succeededFacts(List<PullTaskMemberFact> source) {
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
            return new MemberSnapshot(
                    PullTaskPullCallRosterCheckStatus.SUCCEEDED, Map.copyOf(members));
        }

        private static MemberSnapshot failed(PullTaskPullCallRosterCheckStatus status) {
            return new MemberSnapshot(status, Map.of());
        }

        private GroupParticipantResult member(String identity) {
            return members.get(phone(identity));
        }
    }
}
