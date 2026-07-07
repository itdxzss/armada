package com.armada.platform.protocol.process;

import java.time.Duration;
import java.util.List;

public interface ProtocolProcessCommandRunner {

    ProcessCommandResult run(List<String> command, Duration timeout);
}
