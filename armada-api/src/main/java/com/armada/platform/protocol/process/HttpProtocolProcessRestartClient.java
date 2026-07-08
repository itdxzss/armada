package com.armada.platform.protocol.process;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import org.springframework.stereotype.Component;

@Component
public class HttpProtocolProcessRestartClient implements ProtocolProcessRestartClient {

    private static final String RESTART_ENDPOINT = "/v1/admin/restart-processes";

    private final ProtocolHttpExecutor executor;

    public HttpProtocolProcessRestartClient(ProtocolHttpExecutor executor) {
        this.executor = executor;
    }

    @Override
    public RemoteProtocolRestartResult restart() {
        return executor.postTyped(RESTART_ENDPOINT, null, RemoteProtocolRestartResult.class);
    }
}
