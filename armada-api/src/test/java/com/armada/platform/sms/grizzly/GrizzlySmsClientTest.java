package com.armada.platform.sms.grizzly;

import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsFailure;
import com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest;
import com.armada.platform.sms.grizzly.model.GrizzlySmsStatus;
import com.armada.platform.sms.grizzly.model.GrizzlyStatusUpdate;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GrizzlySmsClientTest {

    private MockRestServiceServer server;
    private GrizzlySmsProperties properties;
    private GrizzlySmsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.grizzlysms.com");
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new GrizzlySmsProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-only-key");
        properties.setPurchasesEnabled(true);
        client = new GrizzlySmsClient(builder.build(), new ObjectMapper(), properties);
    }

    @Test
    void balanceUsesAuthenticatedGetAndExactDecimal() {
        server.expect(queryParam("action", "getBalance"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("api_key", "test-only-key"))
                .andRespond(withSuccess("ACCESS_BALANCE:123456789.01234567", MediaType.TEXT_PLAIN));

        assertThat(client.getBalance()).isEqualByComparingTo("123456789.01234567");
        server.verify();
    }

    @Test
    void providerRejectionInsideHttp200IsNotASuccess() {
        server.expect(queryParam("action", "getNumberV2"))
                .andRespond(withSuccess("NO_BALANCE", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.acquireNumber(numberRequest()))
                .isInstanceOfSatisfying(GrizzlySmsException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo(GrizzlySmsFailure.NO_BALANCE);
                    assertThat(ex.isOutcomeUnknown()).isFalse();
                    assertThat(ex.getCause()).isNull();
                });
        server.verify();
    }

    @Test
    void receivedCodeRetainsLeadingZerosAndIsRedactedInToString() {
        server.expect(queryParam("action", "getStatus"))
                .andExpect(queryParam("id", "123"))
                .andRespond(withSuccess("STATUS_OK:001234", MediaType.TEXT_PLAIN));

        GrizzlySmsStatus status = client.getStatus("123");

        assertThat(status.state()).isEqualTo(GrizzlySmsStatus.State.RECEIVED);
        assertThat(status.code()).contains("001234");
        assertThat(status.toString()).doesNotContain("001234");
        server.verify();
    }

    @Test
    void waitingRetryKeepsPreviousCodeSeparateFromReceivedCode() {
        server.expect(queryParam("action", "getStatus"))
                .andRespond(withSuccess("STATUS_WAIT_RETRY:001234", MediaType.TEXT_PLAIN));

        GrizzlySmsStatus status = client.getStatus("123");

        assertThat(status.state()).isEqualTo(GrizzlySmsStatus.State.WAITING_RETRY);
        assertThat(status.code()).isEmpty();
        assertThat(status.previousCode()).contains("001234");
        assertThat(status.toString()).doesNotContain("001234");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"STATUS_WAIT_CODE,WAITING_CODE", "STATUS_WAIT_RESEND,WAITING_RESEND", "STATUS_CANCEL,CANCELLED"})
    void preservesDistinctWaitingAndCancelledStates(String response, GrizzlySmsStatus.State state) {
        server.expect(queryParam("action", "getStatus")).andRespond(withSuccess(response, MediaType.TEXT_PLAIN));

        assertThat(client.getStatus("123")).satisfies(status -> {
            assertThat(status.state()).isEqualTo(state);
            assertThat(status.code()).isEmpty();
            assertThat(status.previousCode()).isEmpty();
        });
        server.verify();
    }

    @Test
    void acquisitionUsesPriceLimitsFiltersAndPreservesMetadataWithoutAssumingTimezone() {
        var options = new GrizzlyNumberRequest.Options(new BigDecimal("0.10"),
                List.of("1", "3"), List.of("7"), List.of("1628", "147220"));
        server.expect(queryParam("action", "getNumberV2"))
                .andExpect(queryParam("service", "wa"))
                .andExpect(queryParam("country", "any"))
                .andExpect(queryParam("maxPrice", "0.35"))
                .andExpect(queryParam("minPrice", "0.10"))
                .andExpect(queryParam("providerIds", "1%2C3"))
                .andExpect(queryParam("exceptProviderIds", "7"))
                .andExpect(queryParam("phoneException", "1628%2C147220"))
                .andRespond(withSuccess(activationResponse(), MediaType.APPLICATION_JSON));

        var activation = client.acquireNumber(new GrizzlyNumberRequest("wa", "any", new BigDecimal("0.35"), options));

        assertThat(activation.activationId()).isEqualTo("495357953");
        assertThat(activation.phoneNumber()).isEqualTo("18036181752");
        assertThat(activation.cost()).isEqualByComparingTo("0.35");
        assertThat(activation.currency()).isEqualTo(643);
        assertThat(activation.details().countryCode()).contains("12");
        assertThat(activation.details().activationTime()).contains("2026-05-07 13:58:16");
        assertThat(activation.details().activationEnd()).contains("2026-05-07 14:18:16");
        assertThat(activation.details().activationCancel()).contains("2026-05-07 14:03:16");
        assertThat(activation.details().canGetAnotherSms()).contains("0");
        assertThat(activation.toString()).doesNotContain("18036181752");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1234567890123456789", "1E-8"})
    void acquisitionParsesJsonAmountsWithoutDoublePrecisionLoss(String cost) {
        server.expect(queryParam("action", "getNumberV2"))
                .andRespond(withSuccess(activationResponse().replace("0.35", cost), MediaType.APPLICATION_JSON));

        assertThat(client.acquireNumber(numberRequest()).cost()).isEqualByComparingTo(cost);
        server.verify();
    }

    @Test
    void acquisitionAcceptsDocumentedOlderResponseWithoutNewOptionalTimestamps() {
        server.expect(queryParam("action", "getNumberV2"))
                .andRespond(withSuccess("""
                        {"activationId":"123","phoneNumber":"18036181752","activationCost":"0.35","currency":"840"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.acquireNumber(numberRequest()).details().activationCancel()).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0123", "123456789012345678901234567890123"})
    void acquisitionNeverReturnsAnIdThatCannotBeUsedToQueryItsStatus(String activationId) {
        server.expect(queryParam("action", "getNumberV2"))
                .andRespond(withSuccess(activationResponse().replace("495357953", "\"" + activationId + "\""),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.acquireNumber(numberRequest()))
                .isInstanceOfSatisfying(GrizzlySmsException.class, ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
        server.verify();
    }

    @Test
    void emptyOptionalMetadataDoesNotDiscardAConfirmedPurchase() {
        server.expect(queryParam("action", "getNumberV2"))
                .andRespond(withSuccess("""
                        {"activationId":"123","phoneNumber":"18036181752","activationCost":"0.35","currency":840,
                         "countryCode":null,"activationTime":" ","activationEnd":null,"activationCancel":"",
                         "canGetAnotherSms":""}
                        """, MediaType.APPLICATION_JSON));

        var activation = client.acquireNumber(numberRequest());
        assertThat(activation.activationId()).isEqualTo("123");
        assertThat(activation.details().countryCode()).isEmpty();
        assertThat(activation.details().activationTime()).isEmpty();
        assertThat(activation.details().activationEnd()).isEmpty();
        assertThat(activation.details().activationCancel()).isEmpty();
        assertThat(activation.details().canGetAnotherSms()).isEmpty();
        server.verify();
    }

    @Test
    void optionalCountryNamesAllowEmptyValuesWithoutDiscardingTheCountry() {
        server.expect(queryParam("action", "getCountries")).andRespond(withSuccess("""
                {"id":112,"eng":"Country","chn":"","rus":" "}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.getCountries()).singleElement().satisfies(country -> {
            assertThat(country.chineseName()).isEmpty();
            assertThat(country.russianName()).isEmpty();
        });
        server.verify();
    }

    @Test
    void optionalMetadataStillRejectsControlCharactersWithoutExposingTheRawValue() {
        server.expect(queryParam("action", "getNumberV2"))
                .andRespond(withSuccess(activationResponse().replace("2026-05-07 14:03:16", "\\n"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.acquireNumber(numberRequest()))
                .isInstanceOfSatisfying(GrizzlySmsException.class, ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_NUMBERS", "BAD_KEY", "NO_KEY", "NO_BALANCE", "BAD_ACTION", "BAD_SERVICE", "BAD_STATUS",
            "NO_ACTIVATION", "SERVICE_UNAVAILABLE_REGION", "The service is prohibited for sale by administration"})
    void explicitDocumentedRejectionsDoNotClaimAnUnknownPurchase(String response) {
        server.expect(queryParam("action", "getNumberV2")).andRespond(withSuccess(response, MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.acquireNumber(numberRequest()))
                .isInstanceOfSatisfying(GrizzlySmsException.class, ex -> {
                    assertThat(ex.isOutcomeUnknown()).isFalse();
                    assertThat(ex.getReason()).isNotIn(GrizzlySmsFailure.INVALID_RESPONSE, GrizzlySmsFailure.TRANSPORT_ERROR);
                });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "{}", "ACCESS_NUMBER:123:18036181752", "ERROR_SQL",
            "unknown test-only-key 18036181752 001234"})
    void ambiguousPurchaseResponseNeverSuggestsSafeReplayOrLeaksProviderText(String response) {
        server.expect(queryParam("action", "getNumberV2")).andRespond(withSuccess(response, MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.acquireNumber(numberRequest()))
                .isInstanceOfSatisfying(GrizzlySmsException.class, ex -> {
                    assertThat(ex.isOutcomeUnknown()).isTrue();
                    assertThat(ex.getMessage()).doesNotContain("test-only-key", "18036181752", "001234");
                    assertThat(ex.getCause()).isNull();
                });
        server.verify();
    }

    @Test
    void purchaseRejectsTrailingJsonInsteadOfAcceptingAnAmbiguousSuccess() {
        server.expect(queryParam("action", "getNumberV2"))
                .andRespond(withSuccess(activationResponse() + "{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.acquireNumber(numberRequest()))
                .isInstanceOfSatisfying(GrizzlySmsException.class, ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
        server.verify();
    }

    @Test
    void purchaseTransportFailureIsUnknownAndNotRetriedOrExposed() {
        server.expect(queryParam("action", "getNumberV2")).andRespond(request -> {
            throw new IOException("timeout api_key=test-only-key&phone=18036181752 code=001234");
        });

        assertThatThrownBy(() -> client.acquireNumber(numberRequest()))
                .isInstanceOfSatisfying(GrizzlySmsException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo(GrizzlySmsFailure.TRANSPORT_ERROR);
                    assertThat(ex.isOutcomeUnknown()).isTrue();
                    assertThat(ex.getCause()).isNull();
                    assertThat(ex.toString()).doesNotContain("test-only-key", "18036181752", "001234");
                });
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"FOUND", "BAD_REQUEST", "TOO_MANY_REQUESTS", "BAD_GATEWAY"})
    void unsuccessfulHttpPurchaseIsUnknownEvenWhenBodyClaimsSuccess(HttpStatus status) {
        server.expect(queryParam("action", "getNumberV2"))
                .andRespond(withStatus(status).body(activationResponse()));

        assertThatThrownBy(() -> client.acquireNumber(numberRequest()))
                .isInstanceOfSatisfying(GrizzlySmsException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo(GrizzlySmsFailure.HTTP_ERROR);
                    assertThat(ex.isOutcomeUnknown()).isTrue();
                });
        server.verify();
    }

    @Test
    void readTransportFailureIsNotMarkedAsAnUnknownMutation() {
        server.expect(queryParam("action", "getBalance")).andRespond(request -> { throw new IOException("network"); });

        assertThatThrownBy(client::getBalance).isInstanceOfSatisfying(GrizzlySmsException.class,
                ex -> assertThat(ex.isOutcomeUnknown()).isFalse());
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(GrizzlyStatusUpdate.class)
    void stateUpdateUsesTheExactWireActionAndAcknowledgement(GrizzlyStatusUpdate update) {
        server.expect(queryParam("action", "setStatus"))
                .andExpect(queryParam("id", "123"))
                .andExpect(queryParam("status", update.wireValue()))
                .andRespond(withSuccess(update.acknowledgement(), MediaType.TEXT_PLAIN));

        client.setStatus("123", update);
        server.verify();
    }

    @Test
    void cancellationDoesNotAcceptAnUnrelatedSuccessAcknowledgement() {
        server.expect(queryParam("action", "setStatus")).andRespond(withSuccess("ACCESS_READY", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.setStatus("123", GrizzlyStatusUpdate.CANCEL))
                .isInstanceOfSatisfying(GrizzlySmsException.class, ex -> assertThat(ex.isOutcomeUnknown()).isTrue());
        server.verify();
    }

    @Test
    void disabledOrMissingCredentialsFailBeforeAnyHttpRequest() {
        properties.setEnabled(false);
        assertThatThrownBy(client::getBalance).isInstanceOfSatisfying(GrizzlySmsException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(GrizzlySmsFailure.DISABLED));
        properties.setEnabled(true);
        properties.setApiKey(" ");
        assertThatThrownBy(client::getBalance).isInstanceOfSatisfying(GrizzlySmsException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(GrizzlySmsFailure.CREDENTIALS_MISSING));
        server.verify();
    }

    @Test
    void mutationGateBlocksPurchaseAndCancellationButAllowsBalance() {
        properties.setPurchasesEnabled(false);
        assertThatThrownBy(() -> client.acquireNumber(numberRequest())).isInstanceOfSatisfying(GrizzlySmsException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(GrizzlySmsFailure.MUTATIONS_DISABLED));
        assertThatThrownBy(() -> client.setStatus("123", GrizzlyStatusUpdate.CANCEL))
                .isInstanceOfSatisfying(GrizzlySmsException.class,
                        ex -> assertThat(ex.getReason()).isEqualTo(GrizzlySmsFailure.MUTATIONS_DISABLED));
        server.expect(queryParam("action", "getBalance")).andRespond(withSuccess("ACCESS_BALANCE:0", MediaType.TEXT_PLAIN));
        assertThat(client.getBalance()).isZero();
        server.verify();
    }

    @Test
    void validationRejectsMissingPriceMalformedIdsAndInvertedPriceRangeWithoutHttp() {
        assertThatThrownBy(() -> client.acquireNumber(new GrizzlyNumberRequest("wa", "12", null, null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code()));
        assertThatThrownBy(() -> client.getStatus("123&api_key=other")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.getPrices("wa", "+86")).isInstanceOf(BusinessException.class);
        var options = new GrizzlyNumberRequest.Options(new BigDecimal("1.00"), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> client.acquireNumber(new GrizzlyNumberRequest("wa", "12", new BigDecimal("0.35"), options)))
                .isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test
    void apiKeyIsEncodedAsAQueryValueInsteadOfInjectingExtraParameters() {
        properties.setApiKey("test+only/key &country=evil");
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/stubs/handler_api.php");
            assertThat(request.getURI().getRawQuery()).contains("test%2Bonly%2Fkey%20%26country%3Devil");
            assertThat(request.getURI().getRawQuery().split("&")).hasSize(2);
        }).andRespond(withSuccess("ACCESS_BALANCE:1.2", MediaType.TEXT_PLAIN));

        assertThat(client.getBalance()).isEqualByComparingTo("1.2");
        server.verify();
    }

    @Test
    void catalogsSupportDocumentedWrappersAndCountryObjectMap() {
        server.expect(queryParam("action", "getServicesList")).andRespond(withSuccess("""
                {"status":"success","services":[{"code":"wa","name":"WhatsApp"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(queryParam("action", "getCountries")).andRespond(withSuccess("""
                {"12":{"id":12,"eng":"Country","chn":"国家","rus":"Country"}}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.getServices()).singleElement().satisfies(service -> assertThat(service.code()).isEqualTo("wa"));
        assertThat(client.getCountries()).singleElement().satisfies(country -> {
            assertThat(country.id()).isEqualTo("12");
            assertThat(country.chineseName()).contains("国家");
        });
        server.verify();
    }

    @Test
    void catalogArraysMatchPublishedSwaggerAndAllowAnEmptyList() {
        server.expect(queryParam("action", "getServicesList")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(queryParam("action", "getCountries")).andRespond(withSuccess("""
                [{"id":0,"eng":"Country"}]
                """, MediaType.APPLICATION_JSON));

        assertThat(client.getServices()).isEmpty();
        assertThat(client.getCountries()).singleElement().satisfies(country -> {
            assertThat(country.id()).isEqualTo("0");
            assertThat(country.chineseName()).isEmpty();
        });
        server.verify();
    }

    @Test
    void serviceDisplayNameWhitespaceDoesNotBlockWhatsappCatalog() {
        server.expect(queryParam("action", "getServicesList")).andRespond(withSuccess("""
                {"status":"success","services":[
                    {"code":"bik","name":"Hanwha Life\\t"},
                    {"code":"wa","name":"\\t Whatsapp \\r\\n"}
                ]}
                """, MediaType.APPLICATION_JSON));

        var services = client.getServices();

        assertThat(services).hasSize(2);
        assertThat(services.get(0).name()).isEqualTo("Hanwha Life");
        assertThat(services).filteredOn(service -> "wa".equals(service.code()))
                .singleElement().satisfies(service -> assertThat(service.name()).isEqualTo("Whatsapp"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hanwha\tLife", "What\nsapp", "What\u0000sapp", "\t \r\n"})
    void serviceDisplayNameStillRejectsInternalControlsAndBlankNames(String name) throws Exception {
        String response = new ObjectMapper().writeValueAsString(List.of(Map.of("code", "wa", "name", name)));
        server.expect(queryParam("action", "getServicesList"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::getServices).isInstanceOfSatisfying(GrizzlySmsException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(GrizzlySmsFailure.INVALID_RESPONSE));
        server.verify();
    }

    @Test
    void serviceDisplayNameStillRejectsNamesOverTheFieldLimit() throws Exception {
        String response = new ObjectMapper().writeValueAsString(
                List.of(Map.of("code", "wa", "name", "a".repeat(257))));
        server.expect(queryParam("action", "getServicesList"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::getServices).isInstanceOfSatisfying(GrizzlySmsException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(GrizzlySmsFailure.INVALID_RESPONSE));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wa\t", "\twa"})
    void serviceCodesAreNotNormalizedWithDisplayNames(String code) throws Exception {
        String response = new ObjectMapper().writeValueAsString(List.of(Map.of("code", code, "name", "Whatsapp")));
        server.expect(queryParam("action", "getServicesList"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::getServices).isInstanceOfSatisfying(GrizzlySmsException.class,
                exception -> assertThat(exception.getReason()).isEqualTo(GrizzlySmsFailure.INVALID_RESPONSE));
        server.verify();
    }

    @Test
    void singletonCountryMatchesTheCurrentOfficialExample() {
        server.expect(queryParam("action", "getCountries")).andRespond(withSuccess("""
                {"id":112,"eng":"Country","chn":"国家","rus":"Country"}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.getCountries()).singleElement().satisfies(country -> assertThat(country.id()).isEqualTo("112"));
        server.verify();
    }

    @Test
    void priceResponseIsParsedByCountryAndServiceWithExactCostAndIntegerStock() {
        server.expect(queryParam("action", "getPrices"))
                .andExpect(queryParam("country", "12"))
                .andExpect(queryParam("service", "wa"))
                .andRespond(withSuccess("""
                        {"12":{"wa":{"cost":0.1234567890123456789,"count":100}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getPrices("wa", "12")).singleElement().satisfies(price -> {
            assertThat(price.country()).isEqualTo("12");
            assertThat(price.service()).isEqualTo("wa");
            assertThat(price.cost()).isEqualByComparingTo("0.1234567890123456789");
            assertThat(price.count()).isEqualTo(100);
        });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATUS_OK:", "STATUS_WAIT_RETRY:", "STATUS_UNKNOWN:001234", "{\"code\":\"001234\"}"})
    void invalidStatusNeverInventsACodeOrReturnsRawProviderError(String response) {
        server.expect(queryParam("action", "getStatus")).andRespond(withSuccess(response, MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.getStatus("123")).isInstanceOfSatisfying(GrizzlySmsException.class,
                ex -> assertThat(ex.getMessage()).doesNotContain("001234"));
        server.verify();
    }

    private String activationResponse() {
        return """
                {"activationId":495357953,"phoneNumber":"18036181752","activationCost":0.35,"currency":643,
                 "countryCode":"12","canGetAnotherSms":"0","activationTime":"2026-05-07 13:58:16",
                 "activationEnd":"2026-05-07 14:18:16","activationCancel":"2026-05-07 14:03:16"}
                """;
    }

    private GrizzlyNumberRequest numberRequest() {
        return new GrizzlyNumberRequest("wa", "12", new BigDecimal("0.35"), null);
    }
}
