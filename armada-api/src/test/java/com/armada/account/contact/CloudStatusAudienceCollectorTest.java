package com.armada.account.contact;

import com.armada.account.contact.service.CloudStatusAudienceCollector;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.CloudContactsPage;
import com.armada.platform.protocol.port.ContactPort;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;

class CloudStatusAudienceCollectorTest {
    private final ContactPort port = mock(ContactPort.class);
    private final ProtocolAccountRef account = new ProtocolAccountRef(12L, ProtocolBackend.ANDROID, "owned-test", "12025550101");
    @Test void collectsEveryPageAndDeduplicatesWithoutConvertingLidToPhone() {
        when(port.cloudPage(any())).thenReturn(new CloudContactsPage(List.of("10001@lid"), "v1", "c1", true),
                new CloudContactsPage(List.of("10001@lid", "10002@lid"), "v1", "", false));
        var result = new CloudStatusAudienceCollector(port).collect(account);
        assertThat(result.jids()).containsExactly("10001@lid", "10002@lid");
        verify(port).cloudPage(argThat(q -> "c1".equals(q.cursor())));
    }
    @Test void rejectsChangedVersionInsteadOfPublishingPartialSnapshot() {
        when(port.cloudPage(any())).thenReturn(new CloudContactsPage(List.of("10001@lid"), "v1", "c1", true),
                new CloudContactsPage(List.of("10002@lid"), "v2", "", false));
        assertThatThrownBy(() -> new CloudStatusAudienceCollector(port).collect(account))
                .hasMessage("CLOUD_VERSION_CHANGED");
    }
    @Test void rejectsRepeatedCursorAndInvalidIdentity() {
        when(port.cloudPage(any())).thenReturn(new CloudContactsPage(List.of("10001@lid"), "v1", "c1", true));
        assertThatThrownBy(() -> new CloudStatusAudienceCollector(port).collect(account)).hasMessage("CLOUD_CURSOR_REPEATED");
        when(port.cloudPage(any())).thenReturn(new CloudContactsPage(List.of("10001:2@lid"), "v1", "", false));
        assertThatThrownBy(() -> new CloudStatusAudienceCollector(port).collect(account)).hasMessage("CLOUD_INVALID_PAGE");
    }
    @Test void rejectsOversizedPageAndMissingVersion() {
        when(port.cloudPage(any())).thenReturn(new CloudContactsPage(java.util.Collections.nCopies(101,"10001@lid"), "v1", "", false));
        assertThatThrownBy(() -> new CloudStatusAudienceCollector(port).collect(account)).hasMessage("CLOUD_INVALID_PAGE");
        when(port.cloudPage(any())).thenReturn(new CloudContactsPage(List.of(), "", "", false));
        assertThatThrownBy(() -> new CloudStatusAudienceCollector(port).collect(account)).hasMessage("CLOUD_INVALID_PAGE");
    }
}
