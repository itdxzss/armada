package com.armada.feed.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.feed.task.model.enums.FeedTaskAction;
import com.armada.feed.task.model.enums.FeedTaskRunStatus;
import org.junit.jupiter.api.Test;

/** 动态发布任务状态机单测。 */
class FeedTaskStateMachineTest {

    @Test
    void acceptsOnlyDocumentedTransitions() {
        assertThat(FeedTaskStateMachine.next(
                FeedTaskRunStatus.NOT_STARTED, FeedTaskAction.START))
                .contains(FeedTaskRunStatus.RUNNING);
        assertThat(FeedTaskStateMachine.next(
                FeedTaskRunStatus.RUNNING, FeedTaskAction.PAUSE))
                .contains(FeedTaskRunStatus.PAUSED);
        assertThat(FeedTaskStateMachine.next(
                FeedTaskRunStatus.RUNNING, FeedTaskAction.STOP))
                .contains(FeedTaskRunStatus.STOPPED);
        assertThat(FeedTaskStateMachine.next(
                FeedTaskRunStatus.PAUSED, FeedTaskAction.RESUME))
                .contains(FeedTaskRunStatus.RUNNING);
        assertThat(FeedTaskStateMachine.next(
                FeedTaskRunStatus.PAUSED, FeedTaskAction.STOP))
                .contains(FeedTaskRunStatus.STOPPED);
    }

    @Test
    void rejectsInvalidOrTerminalTransitions() {
        for (FeedTaskAction action : FeedTaskAction.values()) {
            assertThat(FeedTaskStateMachine.next(FeedTaskRunStatus.COMPLETED, action)).isEmpty();
            assertThat(FeedTaskStateMachine.next(FeedTaskRunStatus.STOPPED, action)).isEmpty();
        }
        assertThat(FeedTaskStateMachine.next(
                FeedTaskRunStatus.NOT_STARTED, FeedTaskAction.PAUSE)).isEmpty();
        assertThat(FeedTaskStateMachine.next(
                FeedTaskRunStatus.RUNNING, FeedTaskAction.START)).isEmpty();
        assertThat(FeedTaskStateMachine.next(null, FeedTaskAction.START)).isEmpty();
        assertThat(FeedTaskStateMachine.next(FeedTaskRunStatus.RUNNING, null)).isEmpty();
    }

    @Test
    void onlyNotStartedTasksAreEditable() {
        assertThat(FeedTaskStateMachine.isEditable(FeedTaskRunStatus.NOT_STARTED)).isTrue();
        assertThat(FeedTaskStateMachine.isEditable(FeedTaskRunStatus.RUNNING)).isFalse();
        assertThat(FeedTaskStateMachine.isEditable(FeedTaskRunStatus.PAUSED)).isFalse();
        assertThat(FeedTaskStateMachine.isEditable(FeedTaskRunStatus.COMPLETED)).isFalse();
        assertThat(FeedTaskStateMachine.isEditable(FeedTaskRunStatus.STOPPED)).isFalse();
    }
}
