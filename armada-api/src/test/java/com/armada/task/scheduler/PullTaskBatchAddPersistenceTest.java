package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class PullTaskBatchAddPersistenceTest {

    @Test
    void registersAsSpringComponent() {
        assertThat(PullTaskBatchAddPersistence.class.getAnnotation(Component.class))
                .isNotNull();
    }
}
