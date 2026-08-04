package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.armada.platform.kafka.consumer.group.ProtocolGroupActionResultReportedEvent;
import com.armada.task.model.dto.PullTaskContactSaveCallback;
import com.armada.task.model.enums.PullTaskContactSaveOutcome;
import com.armada.task.model.dto.PullTaskPullerInviteCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.enums.PullTaskMaterialAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;
import com.armada.task.service.impl.ProtocolGroupActionResultAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProtocolGroupActionResultAdapterTest {

    private final PullTaskContactSaveResultService service =
            mock(PullTaskContactSaveResultService.class);
    private final PullTaskPullerInviteResultService inviteService =
            mock(PullTaskPullerInviteResultService.class);
    private final PullTaskProtocolResultCallbackService callbackService =
            mock(PullTaskProtocolResultCallbackService.class);
    private final ProtocolGroupActionResultAdapter adapter =
            new ProtocolGroupActionResultAdapter(service, inviteService, callbackService);

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
}
