package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskActionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class PullTaskManagerAdminCandidateSelectorTest {

    private final PullTaskManagerAdminCandidateSelector selector =
            new PullTaskManagerAdminCandidateSelector();

    @Test
    void verificationActionWinsBeforeAnUntriedCandidate() {
        PullTaskGroupAccount firstRole = role(503L, 906L);
        PullTaskAccountAction submitted = action(
                701L, firstRole.getId(), PullTaskActionStatus.SUBMITTED, null);

        var selected = selector.select(
                List.of(candidate(906L), candidate(887L)),
                List.of(firstRole), List.of(submitted), 501L).orElseThrow();

        assertThat(selected.candidate().accountId()).isEqualTo(906L);
        assertThat(selected.action()).isSameAs(submitted);
    }

    @Test
    void untriedCandidateWinsBeforeOldestRetryableAction() {
        PullTaskGroupAccount firstRole = role(503L, 906L);
        PullTaskAccountAction retryable = action(
                701L, firstRole.getId(), PullTaskActionStatus.UNKNOWN, true);

        var selected = selector.select(
                List.of(candidate(906L), candidate(887L)),
                List.of(firstRole), List.of(retryable), 501L).orElseThrow();

        assertThat(selected.candidate().accountId()).isEqualTo(887L);
        assertThat(selected.action()).isNull();
    }

    @Test
    void permanentlyFailedActorIsNeverSelectedAgain() {
        PullTaskGroupAccount role = role(503L, 906L);
        PullTaskAccountAction failed = action(
                701L, role.getId(), PullTaskActionStatus.FAILED, false);

        assertThat(selector.select(
                List.of(candidate(906L)), List.of(role), List.of(failed), 501L))
                .isEmpty();
    }

    private static GroupExecutionAccount candidate(long accountId) {
        return new GroupExecutionAccount(
                accountId, "web", "protocol-" + accountId,
                String.valueOf(accountId), true);
    }

    private static PullTaskGroupAccount role(long id, long accountId) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setAccountId(accountId);
        return row;
    }

    private static PullTaskAccountAction action(
            long id,
            long actorRoleId,
            PullTaskActionStatus status,
            Boolean retryable) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(id);
        row.setActorGroupAccountId(actorRoleId);
        row.setTargetGroupAccountId(501L);
        row.setActionStatus(status.code());
        row.setRetryable(retryable);
        return row;
    }
}
