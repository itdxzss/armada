package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import org.junit.jupiter.api.Test;

class PullTaskExecutionStageRouterTest {

    @Test
    void routesManagerPullerContactThroughTheSharedStageRouter() {
        PullTaskLinkValidationProcessor link = mock(PullTaskLinkValidationProcessor.class);
        PullTaskManagerJoinProcessor manager = mock(PullTaskManagerJoinProcessor.class);
        PullTaskManagerPullerContactProcessor contact =
                mock(PullTaskManagerPullerContactProcessor.class);
        PullTaskExecutionStageRouter router =
                new PullTaskExecutionStageRouter(
                        link, manager, mock(PullTaskManagerAdminProcessor.class), contact,
                        mock(PullTaskPullerInviteProcessor.class),
                        mock(PullTaskPullExecutionProcessor.class),
                        mock(PullTaskMaterialAdminProcessor.class));
        PullTaskGroupExecution candidate = new PullTaskGroupExecution();
        candidate.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        when(contact.process(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(router.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verify(contact).process(candidate, "worker-1", 1_000L);
    }

    @Test
    void routesPullerInviteThroughTheSharedStageRouter() {
        PullTaskLinkValidationProcessor link = mock(PullTaskLinkValidationProcessor.class);
        PullTaskManagerJoinProcessor manager = mock(PullTaskManagerJoinProcessor.class);
        PullTaskManagerPullerContactProcessor contact =
                mock(PullTaskManagerPullerContactProcessor.class);
        PullTaskPullerInviteProcessor invite = mock(PullTaskPullerInviteProcessor.class);
        PullTaskExecutionStageRouter router =
                new PullTaskExecutionStageRouter(
                        link, manager, mock(PullTaskManagerAdminProcessor.class), contact, invite,
                        mock(PullTaskPullExecutionProcessor.class),
                        mock(PullTaskMaterialAdminProcessor.class));
        PullTaskGroupExecution candidate = new PullTaskGroupExecution();
        candidate.setStage(PullTaskExecutionStage.PULLER_INVITE.code());
        when(invite.process(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(router.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(invite).process(candidate, "worker-1", 1_000L);
    }

    @Test
    void routesPullExecutionThroughTheSharedStageRouter() {
        PullTaskPullExecutionProcessor pullExecution =
                mock(PullTaskPullExecutionProcessor.class);
        PullTaskExecutionStageRouter router = new PullTaskExecutionStageRouter(
                mock(PullTaskLinkValidationProcessor.class),
                mock(PullTaskManagerJoinProcessor.class),
                mock(PullTaskManagerAdminProcessor.class),
                mock(PullTaskManagerPullerContactProcessor.class),
                mock(PullTaskPullerInviteProcessor.class), pullExecution,
                mock(PullTaskMaterialAdminProcessor.class));
        PullTaskGroupExecution candidate = new PullTaskGroupExecution();
        candidate.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        when(pullExecution.process(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(router.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(pullExecution).process(candidate, "worker-1", 1_000L);
    }

    @Test
    void routesMaterialAdminThroughTheSharedStageRouter() {
        PullTaskMaterialAdminProcessor materialAdmin =
                mock(PullTaskMaterialAdminProcessor.class);
        PullTaskExecutionStageRouter router = new PullTaskExecutionStageRouter(
                mock(PullTaskLinkValidationProcessor.class),
                mock(PullTaskManagerJoinProcessor.class),
                mock(PullTaskManagerAdminProcessor.class),
                mock(PullTaskManagerPullerContactProcessor.class),
                mock(PullTaskPullerInviteProcessor.class),
                mock(PullTaskPullExecutionProcessor.class), materialAdmin);
        PullTaskGroupExecution candidate = new PullTaskGroupExecution();
        candidate.setStage(PullTaskExecutionStage.MATERIAL_ADMIN.code());
        when(materialAdmin.process(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(router.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(materialAdmin).process(candidate, "worker-1", 1_000L);
    }

    @Test
    void routesClosingThroughThePullExecutionAggregate() {
        PullTaskPullExecutionProcessor pullExecution =
                mock(PullTaskPullExecutionProcessor.class);
        PullTaskExecutionStageRouter router = new PullTaskExecutionStageRouter(
                mock(PullTaskLinkValidationProcessor.class),
                mock(PullTaskManagerJoinProcessor.class),
                mock(PullTaskManagerAdminProcessor.class),
                mock(PullTaskManagerPullerContactProcessor.class),
                mock(PullTaskPullerInviteProcessor.class), pullExecution,
                mock(PullTaskMaterialAdminProcessor.class));
        PullTaskGroupExecution candidate = new PullTaskGroupExecution();
        candidate.setStage(PullTaskExecutionStage.CLOSING.code());
        when(pullExecution.close(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(router.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verify(pullExecution).close(candidate, "worker-1", 1_000L);
    }

    @Test
    void routesManagerAdminBeforeManagerPullerContact() {
        PullTaskManagerAdminProcessor managerAdmin = mock(PullTaskManagerAdminProcessor.class);
        PullTaskManagerPullerContactProcessor contact =
                mock(PullTaskManagerPullerContactProcessor.class);
        PullTaskExecutionStageRouter router = new PullTaskExecutionStageRouter(
                mock(PullTaskLinkValidationProcessor.class),
                mock(PullTaskManagerJoinProcessor.class), managerAdmin, contact,
                mock(PullTaskPullerInviteProcessor.class),
                mock(PullTaskPullExecutionProcessor.class),
                mock(PullTaskMaterialAdminProcessor.class));
        PullTaskGroupExecution candidate = new PullTaskGroupExecution();
        candidate.setStage(PullTaskExecutionStage.MANAGER_ADMIN.code());
        when(managerAdmin.process(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(router.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(managerAdmin).process(candidate, "worker-1", 1_000L);
        verify(contact, org.mockito.Mockito.never())
                .process(candidate, "worker-1", 1_000L);
    }
}
