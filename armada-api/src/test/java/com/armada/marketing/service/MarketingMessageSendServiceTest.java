package com.armada.marketing.service;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MarketingMessageSendServiceTest {
    private final MarketingTaskMapper mapper = mock(MarketingTaskMapper.class);
    private final MessageSendPort port = mock(MessageSendPort.class);
    private final MarketingMessageSendService service = new MarketingMessageSendService(mapper, port);

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void blocksKnownBansAndPreservesBatchOrderingAndOtherGroups() {
        TenantContext.set(7L);
        var bad = command("bad", "banned@g.us", 7L);
        var good = command("good", "healthy@g.us", 7L);
        when(mapper.selectBannedGroupJids(List.of("banned@g.us", "healthy@g.us")))
                .thenReturn(List.of("banned@g.us"));
        when(port.enqueue(List.of(good))).thenReturn(new MessageSendEnqueueResult(
                List.of(MessageSendEnqueueItem.accepted("good"))));
        var result = service.enqueue(List.of(bad, good));
        assertThat(result.items()).extracting(MessageSendEnqueueItem::accepted).containsExactly(false, true);
        assertThat(result.items().get(0).reasonCode()).isEqualTo("GROUP_BANNED");
        verify(port).enqueue(List.of(good));
    }

    @Test
    void allBannedNeverReachOutboxAndExplicitRecoveryIsReadOnNextDispatch() {
        TenantContext.set(7L);
        var command = command("one", "banned@g.us", 7L);
        when(mapper.selectBannedGroupJids(List.of("banned@g.us")))
                .thenReturn(List.of("banned@g.us"), List.of());
        assertThat(service.enqueue(List.of(command)).items().get(0).accepted()).isFalse();
        verifyNoInteractions(port);
        when(port.enqueue(List.of(command))).thenReturn(new MessageSendEnqueueResult(
                List.of(MessageSendEnqueueItem.accepted("one"))));
        assertThat(service.enqueue(List.of(command)).items().get(0).accepted()).isTrue();
    }

    @Test
    void rejectsMissingOrMismatchedTenantBeforeQueryOrDispatch() {
        assertThatThrownBy(() -> service.enqueue(List.of(command("one", "g@g.us", 7L))))
                .isInstanceOf(IllegalArgumentException.class);
        TenantContext.set(8L);
        assertThatThrownBy(() -> service.enqueue(List.of(command("one", "g@g.us", 7L))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper, port);
    }

    @Test
    void duplicateCommandsAndMalformedBackendResultsFailClosed() {
        TenantContext.set(7L);
        var command = command("one", "ok@g.us", 7L);
        assertThatThrownBy(() -> service.enqueue(List.of(command, command)))
                .isInstanceOf(IllegalArgumentException.class);
        when(mapper.selectBannedGroupJids(List.of("ok@g.us"))).thenReturn(List.of());
        when(port.enqueue(List.of(command))).thenReturn(new MessageSendEnqueueResult(
                List.of(MessageSendEnqueueItem.accepted("unexpected"))));
        assertThatThrownBy(() -> service.enqueue(List.of(command)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MessageSendCommand command(String id, String jid, Long tenant) {
        return new MessageSendCommand(null, new MessageSendCommand.MessageTarget(jid), null,
                new MessageSendCommand.MessageCorrelation(tenant, "marketing_task", null, null, null, null),
                id, 0, 0);
    }
}
