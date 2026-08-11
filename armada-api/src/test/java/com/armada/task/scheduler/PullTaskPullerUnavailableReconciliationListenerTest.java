package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.model.dto.PullTaskPullerUnavailableEvent;
import org.junit.jupiter.api.Test;

class PullTaskPullerUnavailableReconciliationListenerTest {

    @Test
    void unavailablePullerTriggersImmediateUnknownResultScan() {
        RecordingScheduler scheduler = new RecordingScheduler();
        PullTaskPullerUnavailableReconciliationListener listener =
                new PullTaskPullerUnavailableReconciliationListener(scheduler);

        listener.onPullerUnavailable(
                new PullTaskPullerUnavailableEvent(7L, 21L, 61L, 5_000L));

        assertThat(scheduler.triggered).isTrue();
    }

    private static final class RecordingScheduler
            extends PullTaskUnknownResultReconciliationScheduler {

        private boolean triggered;

        private RecordingScheduler() {
            super(null, new PullTaskExecutionDispatchProperties());
        }

        @Override
        public void trigger() {
            triggered = true;
        }
    }
}
