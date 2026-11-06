package com.armada.platform.protocol.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolMessageCap;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingAccountMessageCapPortTest {

    private static final ProtocolMessageCap CAP = new ProtocolMessageCap("OK", null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

    @Test
    void routesToTheBackendOfTheAccount() {
        RoutingAccountMessageCapPort port = new RoutingAccountMessageCapPort(List.of(backend(ProtocolBackend.ANDROID)));

        assertThat(port.query(ref(ProtocolBackend.ANDROID), false)).isSameAs(CAP);
    }

    @Test
    void unregisteredBackendIsExplicitlyUnsupported() {
        RoutingAccountMessageCapPort port = new RoutingAccountMessageCapPort(List.of(backend(ProtocolBackend.ANDROID)));

        assertThatThrownBy(() -> port.query(ref(ProtocolBackend.WEB), false))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND));
    }

    @Test
    void duplicateBackendRegistrationFailsFast() {
        assertThatThrownBy(() -> new RoutingAccountMessageCapPort(
                List.of(backend(ProtocolBackend.ANDROID), backend(ProtocolBackend.ANDROID))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AccountMessageCapBackend backend(ProtocolBackend backend) {
        return new AccountMessageCapBackend() {
            @Override
            public ProtocolBackend backend() {
                return backend;
            }

            @Override
            public ProtocolMessageCap query(ProtocolAccountRef account, boolean debug) {
                return CAP;
            }
        };
    }

    private static ProtocolAccountRef ref(ProtocolBackend backend) {
        return new ProtocolAccountRef(1L, backend, "acc_1", "919000000001");
    }
}
