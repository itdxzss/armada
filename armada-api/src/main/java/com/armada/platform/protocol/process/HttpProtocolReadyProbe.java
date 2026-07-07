package com.armada.platform.protocol.process;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class HttpProtocolReadyProbe implements ProtocolReadyProbe {

    @Override
    public ReadyProbeResult probe(String readyUrl, Duration timeout) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(readyUrl))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            return new ReadyProbeResult(readyUrl, statusCode >= 200 && statusCode < 300, statusCode, null);
        } catch (Exception ex) {
            return new ReadyProbeResult(readyUrl, false, null, ex.getMessage());
        }
    }
}
