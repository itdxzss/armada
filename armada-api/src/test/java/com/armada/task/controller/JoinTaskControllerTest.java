package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.task.service.JoinTaskService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JoinTaskControllerTest {

    @Test
    void start_delegatesToServiceAndReturnsEmptySuccessEnvelope() {
        JoinTaskService service = mock(JoinTaskService.class);
        JoinTaskController controller = new JoinTaskController(service);

        ApiResponse<Void> response = controller.start(42L);

        verify(service).startTask(42L);
        assertThat(response.code()).isZero();
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.data()).isNull();
    }
}
