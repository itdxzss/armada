package com.armada.platform.protocol.routing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingGroupProfilePortTest {

    @Test
    void routesSubjectAndPictureByAccountBackend() {
        GroupProfileBackend web = mock(GroupProfileBackend.class);
        GroupProfileBackend android = mock(GroupProfileBackend.class);
        when(web.backend()).thenReturn(ProtocolBackend.WEB);
        when(android.backend()).thenReturn(ProtocolBackend.ANDROID);
        RoutingGroupProfilePort port = new RoutingGroupProfilePort(List.of(web, android));
        ProtocolAccountRef androidAccount = new ProtocolAccountRef(
                7L, ProtocolBackend.ANDROID, "android_7", "919000000001");

        port.updateSubject(androidAccount, "120363001@g.us", "新群名");
        port.updatePicture(androidAccount, "120363001@g.us", null, "aW1hZ2U=");

        verify(android).updateSubject(androidAccount, "120363001@g.us", "新群名");
        verify(android).updatePicture(androidAccount, "120363001@g.us", null, "aW1hZ2U=");
    }
}
