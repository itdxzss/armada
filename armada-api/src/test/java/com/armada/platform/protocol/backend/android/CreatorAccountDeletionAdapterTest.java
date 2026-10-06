package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.http.ProtocolHttpExecutorRegistry;
import com.armada.platform.protocol.model.command.CreatorDeletionCommand;
import com.armada.platform.protocol.model.command.CreatorDeletionObservationRequest;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 真实 HTTP 序列化合同、签名及严格响应边界，不连接协议或真实账号。 */
class CreatorAccountDeletionAdapterTest {
    private static final String SECRET = "unit-test-only-secret-at-least-32-characters";
    private static final String CREATOR = "12025550101";
    private static final String MANAGER = "12025550102";
    private static final String OPERATION = "ptcd_7_10_20_stable_operation";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private AndroidCreatorAccountDeletionAdapter adapter;
    private CreatorDeletionCommand command;

    @BeforeEach
    void setup() throws Exception {
        var builder = RestClient.builder().baseUrl("http://protocol.test");
        server = MockRestServiceServer.bindTo(builder).build();
        var executor = new ProtocolHttpExecutor(builder.build());
        adapter = new AndroidCreatorAccountDeletionAdapter(new ProtocolHttpExecutorRegistry(
                Map.of(ProtocolBackend.ANDROID, executor, ProtocolBackend.WEB, executor)),
                new CreatorDeletionTaskSigner(SECRET));
        command = new CreatorDeletionCommand(7, 10, 20,
                new ProtocolAccountRef(11L, ProtocolBackend.ANDROID, "creator", CREATOR),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(CREATOR.getBytes(StandardCharsets.UTF_8))), "ptgc:7:20", OPERATION);
    }

    @Test
    void signedDeleteAndReadonlyQueryIgnoreCleanupCompleteFalse() throws Exception {
        server.expect(requestTo("http://protocol.test/ws/v1/account/delete/" + CREATOR))
                .andExpect(method(HttpMethod.POST)).andExpect(request -> {
                    String token = request.getHeaders().getFirst("X-Account-Delete-Authorization");
                    assertThat(token).isNotBlank();
                    String[] parts = token.split("\\.");
                    var claims = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
                    assertThat(claims.path("tenantId").asText()).isEqualTo("7");
                    assertThat(claims.path("accountId").asText()).isEqualTo("11");
                    assertThat(claims.path("operationId").asText()).isEqualTo(OPERATION);
                    assertThat(claims.path("createOperationId").asText()).isEqualTo("ptgc:7:20");
                    assertThat(claims.path("method").asText()).isEqualTo("POST");
                    assertThat(claims.path("path").asText()).isEqualTo(request.getURI().getPath());
                    try {
                        Mac mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                        assertThat(Base64.getUrlDecoder().decode(parts[1]))
                                .isEqualTo(mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII)));
                    } catch (java.security.GeneralSecurityException e) { throw new AssertionError(e); }
                }).andRespond(withSuccess(result("ACCEPTED", OPERATION), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://protocol.test/ws/v1/account/deletion/" + CREATOR))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(result("ACCEPTED", OPERATION), MediaType.APPLICATION_JSON));
        assertThat(adapter.delete(command).accepted()).isTrue();
        assertThat(adapter.query(command).accepted()).isTrue();
        server.verify();
    }

    @Test
    void explicitRejectedRecordIsNotLostToHttpErrorHandling() {
        server.expect(requestTo("http://protocol.test/ws/v1/account/deletion/" + CREATOR))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(result("REJECTED", OPERATION)).contentType(MediaType.APPLICATION_JSON));
        assertThat(adapter.query(command).state()).isEqualTo("REJECTED");
        server.verify();
    }

    @Test
    void formattedPhoneUsesTheSameFrozenIdentityAndProtocolRoute() {
        var formatted = new CreatorDeletionCommand(7, 10, 20,
                new ProtocolAccountRef(11L, ProtocolBackend.ANDROID, "creator", "+1 2025550101"),
                command.identityHash(), command.createOperationId(), command.operationId());
        server.expect(requestTo("http://protocol.test/ws/v1/account/deletion/" + CREATOR))
                .andRespond(withSuccess(result("ACCEPTED", OPERATION), MediaType.APPLICATION_JSON));
        assertThat(adapter.query(formatted).accepted()).isTrue();
        server.verify();
    }

    @Test
    void mismatchedOperationAndMissingIqCannotMeanAccepted() {
        server.expect(requestTo("http://protocol.test/ws/v1/account/deletion/" + CREATOR))
                .andRespond(withSuccess(result("ACCEPTED", "other"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.query(command)).hasMessageContaining("不匹配");
        server.verify();
    }

    @Test
    void acceptedRecordWithErrorEnvelopeCannotReleaseTheGate() {
        server.expect(requestTo("http://protocol.test/ws/v1/account/deletion/" + CREATOR))
                .andRespond(withSuccess(result("ACCEPTED", OPERATION).replace("\"Code\":0", "\"Code\":500"),
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.query(command)).hasMessageContaining("冲突");
        server.verify();
    }

    @Test
    void absentAuthorizationFailsBeforeNetwork() {
        var signer = new CreatorDeletionTaskSigner("");
        assertThatThrownBy(() -> signer.sign(command, "POST", "/delete", 1000)).hasMessageContaining("未配置");
        server.verify();
    }

    @Test
    void androidProofUsesIndependentManagerAndStrictFreshFields() {
        server.expect(requestTo("http://protocol.test/ws/v1/account/deletion-proof/" + MANAGER))
                .andExpect(content().json("{\"groupJid\":\"100@g.us\",\"creatorPhone\":\"" + CREATOR + "\"}"))
                .andRespond(withSuccess("{\"Code\":0,\"Data\":" + proof() + "}", MediaType.APPLICATION_JSON));
        var observed = adapter.observe(observation(ProtocolBackend.ANDROID));
        assertThat(observed.creatorCleared()).isTrue();
        assertThat(observed.managerAdmin()).isTrue();
        assertThat(observed.registered()).isFalse();
        server.verify();
    }

    @Test
    void webManagerUsesSameStrictProofSemantics() {
        server.expect(requestTo("http://protocol.test/v1/accounts/manager/deletion-proof"))
                .andRespond(withSuccess(proof(), MediaType.APPLICATION_JSON));
        assertThat(adapter.observe(observation(ProtocolBackend.WEB)).creatorCleared()).isTrue();
        server.verify();
    }

    @Test
    void missingCreatorFieldNeverMeansCreatorCleared() {
        server.expect(requestTo("http://protocol.test/ws/v1/account/deletion-proof/" + MANAGER))
                .andRespond(withSuccess("{\"Code\":0,\"Data\":"
                        + proof().replace("\"creatorPN\":\"\",", "") + "}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.observe(observation(ProtocolBackend.ANDROID)))
                .hasMessageContaining("creatorPN");
    }

    @Test
    void missingRegistrationBooleanNeverMeansUnregistered() {
        server.expect(requestTo("http://protocol.test/v1/accounts/manager/deletion-proof"))
                .andRespond(withSuccess(proof().replace("\"registered\":false,", ""), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.observe(observation(ProtocolBackend.WEB)))
                .hasMessageContaining("registered");
    }

    private CreatorDeletionObservationRequest observation(ProtocolBackend backend) {
        return new CreatorDeletionObservationRequest(new ProtocolAccountRef(12L, backend, "manager", MANAGER),
                command.creator(), "100@g.us");
    }

    private String result(String state, String operation) {
        return "{\"Code\":0,\"Data\":{\"cleanupComplete\":false,\"operation\":{\"operationId\":\""
                + operation + "\",\"accountHash\":\"" + command.identityHash() + "\",\"state\":\""
                + state + "\",\"iqId\":\"42\",\"responseType\":\"result\"}}}";
    }

    private String proof() {
        return "{\"groupJid\":\"100@g.us\",\"creation\":\"1791251749\",\"creator\":\"\",\"creatorPN\":\"\","
                + "\"managerPhone\":\"" + MANAGER + "\",\"managerPresent\":true,\"managerAdmin\":true,"
                + "\"creatorPresent\":false,\"registrationKnown\":true,\"registered\":false,"
                + "\"structureComplete\":true,\"source\":\"LIVE_IQ\",\"queriedAt\":"
                + System.currentTimeMillis() + "}";
    }
}
