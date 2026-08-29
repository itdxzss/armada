package com.armada.contact.task;

import com.armada.contact.task.model.enums.ContactTaskAction;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;
import com.armada.contact.task.service.ContactTaskStateMachine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactTaskStateMachineTest {

    @Test
    void runStatusCodesMatchCompetitorSemantics() {
        assertThat(ContactTaskRunStatus.NOT_STARTED.code()).isZero();
        assertThat(ContactTaskRunStatus.RUNNING.code()).isEqualTo(1);
        assertThat(ContactTaskRunStatus.COMPLETED.code()).isEqualTo(2);
        assertThat(ContactTaskRunStatus.PAUSED.code()).isEqualTo(3);
        assertThat(ContactTaskRunStatus.STOPPED.code()).isEqualTo(4);
        assertThat(ContactTaskRunStatus.fromCode(3)).isEqualTo(ContactTaskRunStatus.PAUSED);
    }

    @Test
    void unknownRunStatusCodeIsRejected() {
        assertThatThrownBy(() -> ContactTaskRunStatus.fromCode(9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionsParseFromLowerCaseWireValues() {
        assertThat(ContactTaskAction.fromWire("start")).isEqualTo(ContactTaskAction.START);
        assertThat(ContactTaskAction.fromWire("PAUSE")).isEqualTo(ContactTaskAction.PAUSE);
        assertThat(ContactTaskAction.fromWire("resume")).isEqualTo(ContactTaskAction.RESUME);
        assertThat(ContactTaskAction.fromWire("stop")).isEqualTo(ContactTaskAction.STOP);
    }

    @Test
    void unknownActionIsRejected() {
        assertThatThrownBy(() -> ContactTaskAction.fromWire("delete"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContactTaskAction.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowedTransitionsFollowCompetitorRules() {
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.NOT_STARTED, ContactTaskAction.START))
                .contains(ContactTaskRunStatus.RUNNING);
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.RUNNING, ContactTaskAction.PAUSE))
                .contains(ContactTaskRunStatus.PAUSED);
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.PAUSED, ContactTaskAction.RESUME))
                .contains(ContactTaskRunStatus.RUNNING);
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.RUNNING, ContactTaskAction.STOP))
                .contains(ContactTaskRunStatus.STOPPED);
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.PAUSED, ContactTaskAction.STOP))
                .contains(ContactTaskRunStatus.STOPPED);
    }

    @Test
    void stoppedAndCompletedAreTerminal() {
        for (ContactTaskAction action : ContactTaskAction.values()) {
            assertThat(ContactTaskStateMachine.next(ContactTaskRunStatus.STOPPED, action))
                    .as("已停止是终态,不可恢复 action=%s", action)
                    .isEmpty();
            assertThat(ContactTaskStateMachine.next(ContactTaskRunStatus.COMPLETED, action))
                    .as("已完成是终态 action=%s", action)
                    .isEmpty();
        }
    }

    @Test
    void rejectsNonsenseTransitions() {
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.NOT_STARTED, ContactTaskAction.PAUSE)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.NOT_STARTED, ContactTaskAction.RESUME)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.NOT_STARTED, ContactTaskAction.STOP)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.RUNNING, ContactTaskAction.START)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.RUNNING, ContactTaskAction.RESUME)).isEmpty();
        assertThat(ContactTaskStateMachine.next(
                ContactTaskRunStatus.PAUSED, ContactTaskAction.PAUSE)).isEmpty();
    }

    @Test
    void onlyNotStartedTasksAreEditable() {
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.NOT_STARTED)).isTrue();
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.RUNNING)).isFalse();
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.PAUSED)).isFalse();
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.COMPLETED)).isFalse();
        assertThat(ContactTaskStateMachine.isEditable(ContactTaskRunStatus.STOPPED)).isFalse();
    }
}
