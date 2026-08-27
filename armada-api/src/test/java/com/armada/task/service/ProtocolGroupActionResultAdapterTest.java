package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.kafka.consumer.group.ProtocolGroupActionResultReportedEvent;
import com.armada.task.model.dto.PullTaskContactSaveCallback;
import com.armada.task.model.dto.PullTaskCreatorLeaveCallback;
import com.armada.task.model.enums.PullTaskContactSaveOutcome;
import com.armada.task.model.enums.PullTaskCreatorLeaveOperation;
import com.armada.task.model.enums.PullTaskCreatorLeaveProtocolOutcome;
import com.armada.task.model.dto.PullTaskPullerInviteCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.dto.PullTaskGroupSettingsCallback;
import com.armada.task.model.dto.PullTaskManagerAdminCallback;
import com.armada.task.model.enums.PullTaskGroupSettingsProtocolOutcome;
import com.armada.task.model.enums.PullTaskManagerAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskMaterialAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;
import com.armada.task.service.impl.ProtocolGroupActionResultAdapter;
import com.armada.task.service.impl.TaskResultOwnerScopeRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProtocolGroupActionResultAdapterTest {

    private final PullTaskContactSaveResultService service =
            mock(PullTaskContactSaveResultService.class);
    private final PullTaskPullerInviteResultService inviteService =
            mock(PullTaskPullerInviteResultService.class);
    private final PullTaskManagerAdminResultService managerAdminResultService =
            mock(PullTaskManagerAdminResultService.class);
    private final PullTaskProtocolResultCallbackService callbackService =
            mock(PullTaskProtocolResultCallbackService.class);
    private final PullTaskGroupSettingsResultService groupSettingsResultService =
            mock(PullTaskGroupSettingsResultService.class);
    private final PullTaskCreatorLeaveResultService creatorLeaveResultService =
            mock(PullTaskCreatorLeaveResultService.class);
    private final TaskResultOwnerScopeRunner ownerScopeRunner = mock(TaskResultOwnerScopeRunner.class);
    private final ProtocolGroupActionResultAdapter adapter;

    ProtocolGroupActionResultAdapterTest() {
        when(ownerScopeRunner.runForPullTask(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        });
        adapter = new ProtocolGroupActionResultAdapter(
                service, inviteService, managerAdminResultService,
                groupSettingsResultService, callbackService, creatorLeaveResultService, ownerScopeRunner);
    }

    @Test
    void creatorLeavePromoteEventRoutesToDedicatedStateMachine() {
        ProtocolGroupActionResultReportedEvent event = new ProtocolGroupActionResultReportedEvent(
                "event-leave-1", 7L, 100L, 11L, 903L,
                "pull_task_creator_leave", "PARTICIPANT_PROMOTE", 901L, "owner-901",
                "cmd-promote-1", 1, "SUCCESS", "919000000082@s.whatsapp.net",
                null, null, false, 5_000L, "worker-a");

        adapter.handleActionResultReported(event);

        ArgumentCaptor<PullTaskCreatorLeaveCallback> captor =
                ArgumentCaptor.forClass(PullTaskCreatorLeaveCallback.class);
        verify(creatorLeaveResultService).apply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PullTaskCreatorLeaveCallback(
                7L, 100L, 11L, 903L, 901L, "owner-901", "cmd-promote-1", 1,
                PullTaskCreatorLeaveOperation.PROMOTE, "919000000082@s.whatsapp.net",
                PullTaskCreatorLeaveProtocolOutcome.SUCCESS, null, null, 5_000L));
    }

    @Test
    void creatorLeaveEventRoutesWithoutTargetJid() {
        ProtocolGroupActionResultReportedEvent event = new ProtocolGroupActionResultReportedEvent(
                "event-leave-2", 7L, 100L, 11L, 904L,
                "pull_task_creator_leave", "GROUP_LEAVE", 901L, "owner-901",
                "cmd-leave-1", 1, "FAILED", null,
                "GROUP_LEAVE_FAILED", "failed", false, 5_001L, "worker-a");

        adapter.handleActionResultReported(event);

        ArgumentCaptor<PullTaskCreatorLeaveCallback> captor =
                ArgumentCaptor.forClass(PullTaskCreatorLeaveCallback.class);
        verify(creatorLeaveResultService).apply(captor.capture());
        assertThat(captor.getValue().operation()).isEqualTo(PullTaskCreatorLeaveOperation.LEAVE);
        assertThat(captor.getValue().targetJid()).isNull();
        assertThat(captor.getValue().outcome())
                .isEqualTo(PullTaskCreatorLeaveProtocolOutcome.FAILED);
    }

    @Test
    void groupSettingsEventRoutesToGroupSettingsStateMachine() {
        // 群设置结果没有 targetJid：它改的是群属性，不针对任何成员。
        ProtocolGroupActionResultReportedEvent event = new ProtocolGroupActionResultReportedEvent(
                "event-9", 7L, 100L, 11L, 811L,
                "pull_task_group_settings", "GROUP_SETTINGS_APPLY", 901L, "manager-901",
                "cmd-settings-1", 2, "FAILED", null,
                "GROUP_PERMISSION_DENIED", "denied", false, 5_000L, "worker-a");

        adapter.handleActionResultReported(event);

        ArgumentCaptor<PullTaskGroupSettingsCallback> captor =
                ArgumentCaptor.forClass(PullTaskGroupSettingsCallback.class);
        verify(groupSettingsResultService).apply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PullTaskGroupSettingsCallback(
                7L, 100L, 11L, 811L, 901L, "manager-901", "cmd-settings-1", 2,
                PullTaskGroupSettingsProtocolOutcome.FAILED, null,
                "GROUP_PERMISSION_DENIED", "denied", 5_000L));
    }

    @Test
    void contactSaveUnknownEventRoutesToStronglyTypedCallback() {
        ProtocolGroupActionResultReportedEvent event = new ProtocolGroupActionResultReportedEvent(
                "event-1", 7L, 100L, 11L, 601L,
                "pull_task_contact_save", "CONTACT_SAVE", 901L, "manager-901",
                "cmd-contact-1", 1, "UNKNOWN", null, "ACCOUNT_BUSY", "busy",
                true, 5_000L, "worker-a");

        adapter.handleActionResultReported(event);

        ArgumentCaptor<PullTaskContactSaveCallback> captor =
                ArgumentCaptor.forClass(PullTaskContactSaveCallback.class);
        verify(service).apply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PullTaskContactSaveCallback(
                7L, 100L, 11L, 601L, 901L, "manager-901", "cmd-contact-1", 1,
                PullTaskContactSaveOutcome.UNKNOWN, "ACCOUNT_BUSY", "busy", true, 5_000L));
    }

    @Test
    void pullerInviteEventRoutesToInviteStateMachine() {
        ProtocolGroupActionResultReportedEvent event = new ProtocolGroupActionResultReportedEvent(
                "event-2", 7L, 100L, 11L, 701L,
                "pull_task_puller_invite", "PARTICIPANT_ADD", 901L, "manager-901",
                "cmd-invite-1", 1, "UNKNOWN", "8613800000902@s.whatsapp.net",
                "PARTICIPANT_ADD_TIMEOUT", "timed out", true, 5_000L, "worker-a");

        adapter.handleActionResultReported(event);

        ArgumentCaptor<PullTaskPullerInviteCallback> captor =
                ArgumentCaptor.forClass(PullTaskPullerInviteCallback.class);
        verify(inviteService).apply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PullTaskPullerInviteCallback(
                7L, 100L, 11L, 701L, 901L, "manager-901", "cmd-invite-1", 1,
                "8613800000902@s.whatsapp.net", PullTaskPullerInviteProtocolOutcome.UNKNOWN,
                "PARTICIPANT_ADD_TIMEOUT", "timed out", true, 5_000L));
    }

    @Test
    void materialAdminEventRoutesToStronglyTypedCallback() {
        ProtocolGroupActionResultReportedEvent event = new ProtocolGroupActionResultReportedEvent(
                "event-3", 7L, 100L, 11L, 601L,
                "pull_task_material_admin", "PARTICIPANT_PROMOTE", 901L, "manager-901",
                "cmd-admin-1", 1, "UNKNOWN", "8613900000001@s.whatsapp.net",
                "MATERIAL_ADMIN_PERMISSION_UNCONFIRMED", "unconfirmed", true,
                5_000L, "worker-a");

        adapter.handleActionResultReported(event);

        ArgumentCaptor<PullTaskMaterialAdminCallback> captor =
                ArgumentCaptor.forClass(PullTaskMaterialAdminCallback.class);
        verify(callbackService).handleMaterialAdmin(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PullTaskMaterialAdminCallback(
                7L, 100L, 11L, 601L, 901L, "manager-901", "cmd-admin-1", 1,
                "8613900000001@s.whatsapp.net",
                PullTaskMaterialAdminProtocolOutcome.UNKNOWN,
                "MATERIAL_ADMIN_PERMISSION_UNCONFIRMED", "unconfirmed", true, 5_000L));
    }

    @Test
    void managerAdminEventRoutesOnlyToManagerAdminStateMachine() {
        ProtocolGroupActionResultReportedEvent event = new ProtocolGroupActionResultReportedEvent(
                "event-4", 7L, 100L, 11L, 711L,
                "pull_task_manager_admin", "PARTICIPANT_PROMOTE", 903L, "promoter-903",
                "cmd-promote-2", 2, "FAILED", "15@s.whatsapp.net",
                "GROUP_PERMISSION_DENIED", "raw", false, 5_000L, "worker-a");

        adapter.handleActionResultReported(event);

        ArgumentCaptor<PullTaskManagerAdminCallback> captor =
                ArgumentCaptor.forClass(PullTaskManagerAdminCallback.class);
        verify(managerAdminResultService).apply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PullTaskManagerAdminCallback(
                7L, 100L, 11L, 711L, 903L, "promoter-903", "cmd-promote-2", 2,
                "15@s.whatsapp.net", PullTaskManagerAdminProtocolOutcome.FAILED,
                "GROUP_PERMISSION_DENIED", "raw", false, 5_000L));
    }
}
