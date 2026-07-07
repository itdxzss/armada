package com.armada.platform.protocol.process;

import java.time.Duration;

public interface ProtocolReadyProbe {

    ReadyProbeResult probe(String readyUrl, Duration timeout);
}
