package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.hyperlink.task.model.enums.HyperlinkTaskAction;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.hyperlink.task.service.HyperlinkTaskStateMachine;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** 后端双状态与四动作状态机测试。 */
class HyperlinkTaskStateMachineTest {

    private final HyperlinkTaskStateMachine stateMachine = new HyperlinkTaskStateMachine();

    @Test
    void fourActionsOnlyAllowFrozenTransitions() {
        assertThat(stateMachine.next(false, HyperlinkTaskRunStatus.NOT_STARTED, HyperlinkTaskAction.START))
                .isEqualTo(HyperlinkTaskRunStatus.NOT_STARTED);
        assertThat(stateMachine.next(true, HyperlinkTaskRunStatus.RUNNING, HyperlinkTaskAction.PAUSE))
                .isEqualTo(HyperlinkTaskRunStatus.PAUSED);
        assertThat(stateMachine.next(true, HyperlinkTaskRunStatus.PAUSED, HyperlinkTaskAction.RESUME))
                .isEqualTo(HyperlinkTaskRunStatus.RUNNING);
        assertThat(stateMachine.next(true, HyperlinkTaskRunStatus.RUNNING, HyperlinkTaskAction.STOP))
                .isEqualTo(HyperlinkTaskRunStatus.STOPPED);
        assertThat(stateMachine.next(true, HyperlinkTaskRunStatus.PAUSED, HyperlinkTaskAction.STOP))
                .isEqualTo(HyperlinkTaskRunStatus.STOPPED);
    }

    @Test
    void terminalAndWrongSourceTransitionsAreRejected() {
        assertIllegal(true, HyperlinkTaskRunStatus.NOT_STARTED, HyperlinkTaskAction.PAUSE);
        assertIllegal(true, HyperlinkTaskRunStatus.RUNNING, HyperlinkTaskAction.START);
        assertIllegal(true, HyperlinkTaskRunStatus.PAUSED, HyperlinkTaskAction.PAUSE);
        assertIllegal(true, HyperlinkTaskRunStatus.COMPLETED, HyperlinkTaskAction.START);
        assertIllegal(true, HyperlinkTaskRunStatus.STOPPED, HyperlinkTaskAction.RESUME);
    }

    private void assertIllegal(
            boolean enabled,
            HyperlinkTaskRunStatus status,
            HyperlinkTaskAction action) {
        assertThatThrownBy(() -> stateMachine.next(enabled, status, action))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(40910);
    }
}
