package com.armada.group.normalcreation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.platform.protocol.model.command.ProtocolNormalGroupCreationCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NormalGroupCreationCommandDispatcherTest {

    @Test
    void failedContactRetryOnlyReissuesFailedDirection() {
        NormalGroupCreationMapper mapper = mock(NormalGroupCreationMapper.class);
        ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
        NormalGroupCreationCommandDispatcher dispatcher =
                new NormalGroupCreationCommandDispatcher(mapper, outbox);
        ItemWork item = new ItemWork(
                21L, 1L, 9L, "普群001", 382L, "creator-web", "WEB", "911",
                null, "FAILED", "PREPARING_CONTACTS", "NONE",
                null, null, null, "KEEP", null, 91L, 92L,
                true, false, true, false, 0);
        MemberWork member = new MemberWork(
                31L, 383L, "member-android", "ANDROID", "922",
                "SUCCESS", "FAILED", "cmd-old-success", "cmd-old-failed", "PENDING");
        when(outbox.enqueueNormalGroupCreationCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd-retry"), 1));
        when(mapper.bindContactCommand(
                eq(31L), eq("MEMBER_SAVE_CREATOR"), eq("FAILED"), eq("cmd-retry"), anyLong()))
                .thenReturn(1);
        when(mapper.markContactPrepareSubmitted(eq(21L), anyLong())).thenReturn(1);

        dispatcher.enqueueFailedContactPrepare(item, List.of(member));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolNormalGroupCreationCommandRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueueNormalGroupCreationCommands(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(command -> {
            assertThat(command.action()).isEqualTo("CONTACT_PREPARE");
            assertThat(command.direction()).isEqualTo("MEMBER_SAVE_CREATOR");
            assertThat(command.actor().protocolAccountId()).isEqualTo("member-android");
        });
        verify(mapper).bindContactCommand(
                eq(31L), eq("MEMBER_SAVE_CREATOR"), eq("FAILED"), eq("cmd-retry"), anyLong());
    }
}
