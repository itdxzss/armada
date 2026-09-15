package com.armada.platform.registration.cobalt;

import com.armada.platform.registration.cobalt.model.CobaltRegistrationState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 独立验证 Armada 到 Cobalt 的会话绑定、幂等 ID 和凭据边界；不访问真实注册服务。 */
class CobaltRegistrationClientTest {
    private MockRestServiceServer server;
    private CobaltRegistrationClient client;
    private CobaltRegistrationProperties properties;
    private static final String SESSION_ID = "reg_1_42";
    private static final String PHONE = "18036181752";

    @BeforeEach
    void setUp() {
        properties = new CobaltRegistrationProperties();
        properties.setEnabled(true);
        properties.setApiToken("test-only-cobalt-token-1234567890");
        RestClient.Builder builder = RestClient.builder().baseUrl("http://cobalt.internal");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CobaltRegistrationClient(builder.build(), new ObjectMapper(), properties);
    }

    @Test
    void disabledClientFailsBeforeHttp() {
        properties.setEnabled(false);
        assertThatThrownBy(client::health).isInstanceOfSatisfying(CobaltRegistrationException.class,
                error -> assertThat(error.getReason()).isEqualTo(CobaltRegistrationFailure.DISABLED));
        server.verify();
    }

    @Test
    void healthUsesBearerAndPreservesAdmissionState() {
        server.expect(requestTo("http://cobalt.internal/v1/health"))
                .andExpect(header("Authorization", "Bearer " + properties.getApiToken()))
                .andRespond(withSuccess("{\"registrationEnabled\":true,\"availableSlots\":0}", MediaType.APPLICATION_JSON));
        assertThat(client.health().availableSlots()).isZero();
        server.verify();
    }

    @Test
    void createUsesStableRegistrationIdAndVerifiesReturnedPhone() {
        server.expect(requestTo("http://cobalt.internal/v1/registrations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"registrationId\":\"reg_1_42\",\"phoneNumber\":\"18036181752\",\"accountType\":1}"))
                .andRespond(withSuccess(snapshot("WAITING_CODE"), MediaType.APPLICATION_JSON));
        var result = client.create(SESSION_ID, PHONE, 1);
        assertThat(result.state()).isEqualTo(CobaltRegistrationState.WAITING_CODE);
        assertThat(result.toString()).doesNotContain(PHONE);
        server.verify();
    }

    @Test
    void codeRetainsLeadingZeroAndUsesExistingSession() {
        server.expect(requestTo("http://cobalt.internal/v1/registrations/reg_1_42/code"))
                .andExpect(content().json("{\"code\":\"001234\"}"))
                .andRespond(withSuccess(snapshot("VERIFYING"), MediaType.APPLICATION_JSON));
        assertThat(client.submitCode(SESSION_ID, "001234").state()).isEqualTo(CobaltRegistrationState.VERIFYING);
        server.verify();
    }

    @Test
    void notFoundHasStableClassificationWithoutRawServerResponse() {
        server.expect(requestTo("http://cobalt.internal/v1/registrations/reg_1_42"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("private-token 001234 " + PHONE));
        assertThatThrownBy(() -> client.status(SESSION_ID))
                .isInstanceOfSatisfying(CobaltRegistrationException.class, error -> {
                    assertThat(error.getReason()).isEqualTo(CobaltRegistrationFailure.NOT_FOUND);
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                    assertThat(error.getMessage()).doesNotContain("private-token", "001234", PHONE);
                    assertThat(error.getCause()).isNull();
                });
        server.verify();
    }

    @Test
    void exportAcceptsOnlyZhuanSixWithMatchingPhoneAndThirtyTwoByteKeys() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        String six = String.join(",", PHONE, key, key, key, key, "ABABABAB-1111-2222-3333-123456789ABC");
        server.expect(requestTo("http://cobalt.internal/v1/registrations/reg_1_42/credentials"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(credential(six), MediaType.APPLICATION_JSON));
        assertThat(client.exportSix(SESSION_ID).sixLine()).isEqualTo(six);
        server.verify();
    }

    @Test
    void malformedKeysAndOriginalCobaltSixthFieldAreRejected() {
        String six = String.join(",", PHONE, "AA==", "AA==", "AA==", "AA==", "identityIdBase64");
        server.expect(requestTo("http://cobalt.internal/v1/registrations/reg_1_42/credentials"))
                .andRespond(withSuccess(credential(six), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.exportSix(SESSION_ID))
                .isInstanceOf(CobaltRegistrationException.class).hasMessageNotContaining(six);
        server.verify();
    }

    @Test
    void mismatchedSessionAndPhoneCannotBeImported() {
        server.expect(requestTo("http://cobalt.internal/v1/registrations"))
                .andRespond(withSuccess(snapshot("REGISTERED").replace(PHONE, "18036181753"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.create(SESSION_ID, PHONE, 1))
                .isInstanceOfSatisfying(CobaltRegistrationException.class,
                        error -> assertThat(error.isOutcomeUnknown()).isTrue());
        server.verify();
    }

    @Test
    void malformedAndTrailingResponsesDoNotInventSuccessfulRegistration() {
        server.expect(requestTo("http://cobalt.internal/v1/registrations/reg_1_42"))
                .andRespond(withSuccess(snapshot("REGISTERED") + "{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.status(SESSION_ID)).isInstanceOf(CobaltRegistrationException.class);
        server.verify();
    }

    private String snapshot(String state) {
        return "{\"registrationId\":\"" + SESSION_ID + "\",\"phoneNumber\":\"" + PHONE
                + "\",\"state\":\"" + state + "\"}";
    }

    private String credential(String six) {
        return "{\"registrationId\":\"" + SESSION_ID + "\",\"phoneNumber\":\"" + PHONE
                + "\",\"format\":\"zhuan-six-v1\",\"sixLine\":\"" + six + "\"}";
    }
}
