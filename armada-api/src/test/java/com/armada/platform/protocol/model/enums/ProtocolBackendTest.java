package com.armada.platform.protocol.model.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProtocolBackendTest {

    @Test
    void explicitProtocolParserAcceptsOnlyKnownBackends() {
        assertThat(ProtocolBackend.fromExplicitProtocolId(" web "))
                .isEqualTo(ProtocolBackend.WEB);
        assertThat(ProtocolBackend.fromExplicitProtocolId("ANDROID"))
                .isEqualTo(ProtocolBackend.ANDROID);
        assertThatThrownBy(() -> ProtocolBackend.fromExplicitProtocolId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtocolBackend.fromExplicitProtocolId("desktop"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
