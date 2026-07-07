package com.armada.platform.protocol.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpProtocolReadyProbeTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void probe_returnsReadyForTwoHundredStatus() throws IOException {
        String url = startServer(200);
        HttpProtocolReadyProbe probe = new HttpProtocolReadyProbe();

        ReadyProbeResult result = probe.probe(url, Duration.ofSeconds(2));

        assertThat(result.readyUrl()).isEqualTo(url);
        assertThat(result.ready()).isTrue();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.error()).isNull();
    }

    @Test
    void probe_returnsNotReadyForUnavailableStatus() throws IOException {
        String url = startServer(503);
        HttpProtocolReadyProbe probe = new HttpProtocolReadyProbe();

        ReadyProbeResult result = probe.probe(url, Duration.ofSeconds(2));

        assertThat(result.ready()).isFalse();
        assertThat(result.statusCode()).isEqualTo(503);
    }

    private String startServer(int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/readyz", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/readyz";
    }
}
