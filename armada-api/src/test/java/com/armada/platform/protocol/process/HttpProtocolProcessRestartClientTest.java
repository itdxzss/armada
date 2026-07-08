package com.armada.platform.protocol.process;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpProtocolProcessRestartClientTest {

    @Test
    void restart_postsToProtocolLayerAdminEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protocol-master.internal");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ProtocolHttpExecutor executor = new ProtocolHttpExecutor(builder.build());
        HttpProtocolProcessRestartClient client = new HttpProtocolProcessRestartClient(executor);

        server.expect(requestTo("http://protocol-master.internal/v1/admin/restart-processes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{}"))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "command": "pm2 restart protocol-worker-1 --update-env && pm2 restart protocol-master --update-env",
                          "masterProcess": "protocol-master",
                          "workerProcesses": ["protocol-worker-1"],
                          "masterRestartScheduled": true,
                          "message": "protocol process restart scheduled"
                        }
                        """, MediaType.APPLICATION_JSON));

        RemoteProtocolRestartResult result = client.restart();

        assertThat(result.success()).isTrue();
        assertThat(result.command()).contains("protocol-master");
        assertThat(result.masterRestartScheduled()).isTrue();
        assertThat(result.workerProcesses()).containsExactly("protocol-worker-1");
        server.verify();
    }
}
