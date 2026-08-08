package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskPullCallRosterCheckStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
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
    private final GroupMemberListPort memberListPort;
    private final PullTaskPullCallParticipantResultService participantResultService;

    /** 创建逐号码异常批次核实服务。 */
    public PullTaskPullCallReconciliationService(
            PullTaskUnknownResultResources resources,
            AccountProtocolLookupService accountLookup,
            GroupMemberListPort memberListPort,
            PullTaskPullCallParticipantResultService participantResultService) {
        this.resources = resources;
        this.accountLookup = accountLookup;
        this.memberListPort = memberListPort;
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
                || call.getSubmittedAt() == null || call.getSubmittedAt() > cutoff) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        List<PullTaskPullCallMemberAttempt> unresolved = attempts.stream()
                .filter(row -> Objects.equals(
                        row.getLifecycleStatus(), PullTaskParticipantAttemptStatus.SUBMITTED.code()))
                .toList();
        if (unresolved.isEmpty()) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        persistMissingAsUncertain(unresolved, now);
        RosterDecision decision = rosterDecision(call, cutoff, now);
        if (!decision.proceed()) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        MemberSnapshot snapshot = decision.query()
                ? queryMembers(execution, accounts, call, now)
                : MemberSnapshot.failed(PullTaskPullCallRosterCheckStatus.FAILED);
        int confirmed = 0;
        int released = 0;
        for (PullTaskPullCallMemberAttempt attempt : unresolved) {
            boolean present = snapshot.member(attempt.getTargetJid()) != null;
            if (participantResultService.settleUncertain(
                    execution.getTenantId(), call, execution, attempt, present, now)) {
                if (present) {
                    confirmed++;
                } else {
                    released++;
                }
            }
        }
        if (decision.finish()
                && resources.callMapper().finishRosterCheck(
                call.getId(), PullTaskPullCallRosterCheckStatus.CLAIMED.code(),
                snapshot.status().code(), now) != 1) {
            throw new IllegalStateException("异常批次名单核实完成状态写入失败");
        }
        return new PullTaskUnknownResultReconciliationStats(confirmed, released);
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
            boolean staleClaim = call.getRosterCheckStartedAt() != null
                    && call.getRosterCheckStartedAt() <= cutoff;
            return staleClaim
                    ? new RosterDecision(true, false, true)
                    : RosterDecision.stop();
        }
        return new RosterDecision(true, false, false);
    }

    private MemberSnapshot queryMembers(
            PullTaskGroupExecution execution,
            List<PullTaskGroupAccount> accounts,
            PullTaskPullCall call,
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
        try {
            List<GroupParticipantResult> members = memberListPort.list(new GroupMemberListQuery(
                    refs.get(0), execution.getGroupJid(),
                    "pull-call-roster-" + call.getId() + "-" + now));
            return MemberSnapshot.succeeded(members);
        } catch (RuntimeException ex) {
            log.warn("普通拉群异常批次名单查询失败 tenantId={} executionId={} callId={} errorType={}",
                    execution.getTenantId(), execution.getId(), call.getId(),
                    ex.getClass().getSimpleName());
            return MemberSnapshot.failed(PullTaskPullCallRosterCheckStatus.FAILED);
        }
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

        private static MemberSnapshot succeeded(List<GroupParticipantResult> source) {
            Map<String, GroupParticipantResult> members = new LinkedHashMap<>();
            if (source != null) {
                source.stream().filter(Objects::nonNull).forEach(member -> members.putIfAbsent(
                        phone(member.phone() == null ? member.jid() : member.phone()), member));
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
