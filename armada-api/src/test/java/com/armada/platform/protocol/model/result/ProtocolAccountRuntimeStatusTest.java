package com.armada.platform.protocol.model.result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolAccountRuntimeStatusTest {

    @Test
    void normalizesNullStateToUnknown() {
        ProtocolAccountRuntimeStatus status = new ProtocolAccountRuntimeStatus(null);

        assertThat(status.state()).isEqualTo("UNKNOWN");
        assertThat(status.online()).isFalse();
    }

    @Test
    void trimsStateAndMatchesOnlineCaseInsensitively() {
        ProtocolAccountRuntimeStatus status = new ProtocolAccountRuntimeStatus("  online  ");

        assertThat(status.state()).isEqualTo("online");
        assertThat(status.online()).isTrue();
    }

    @Test
    void reportsNonOnlineState() {
        ProtocolAccountRuntimeStatus status = new ProtocolAccountRuntimeStatus("OFFLINE");

        assertThat(status.online()).isFalse();
    }
}
