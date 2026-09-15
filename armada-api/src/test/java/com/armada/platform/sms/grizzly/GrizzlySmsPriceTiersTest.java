package com.armada.platform.sms.grizzly;

import com.armada.platform.sms.grizzly.exception.GrizzlySmsException;
import com.armada.platform.sms.grizzly.exception.GrizzlySmsFailure;
import com.armada.platform.sms.grizzly.model.GrizzlyNumberRequest;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GrizzlySmsPriceTiersTest {

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
        client = new GrizzlySmsClient(builder.build(), new ObjectMapper(), properties);
    }

    @Test
    void tiersUsePriceSpecificStockAndMatchAllProvidersWithoutDuplicatingTheirTotalStock() {
        expectTierPrices("""
                {"187":{"wa":{"0.50":7,"0.35":12,"0.60":0}}}
                """);
        expectProviders("""
                {"187":{"wa":{"price":0.35,"count":999,"providers":{
                    "12":{"count":900,"price":[0.35,0.50,0.5],"provider_id":12},
                    "8":{"count":99,"price":["0.350"],"provider_id":"8"}
                }}}}
                """);

        var tiers = client.getPriceTiers("wa", "187");

        assertThat(tiers).hasSize(3);
        assertThat(tiers.get(0)).satisfies(tier -> {
            assertThat(tier.country()).isEqualTo("187");
            assertThat(tier.service()).isEqualTo("wa");
            assertThat(tier.cost()).isEqualByComparingTo("0.35");
            assertThat(tier.count()).isEqualTo(12);
            assertThat(tier.providerIds()).containsExactlyInAnyOrder("12", "8");
        });
        assertThat(tiers.get(1)).satisfies(tier -> {
            assertThat(tier.cost()).isEqualByComparingTo("0.50");
            assertThat(tier.count()).isEqualTo(7);
            assertThat(tier.providerIds()).containsExactly("12");
        });
        assertThat(tiers.get(2)).satisfies(tier -> {
            assertThat(tier.cost()).isEqualByComparingTo("0.60");
            assertThat(tier.count()).isZero();
            assertThat(tier.providerIds()).isEmpty();
        });
        assertThatThrownBy(() -> tiers.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tiers.get(0).providerIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        server.verify();
    }

    @Test
    void independentProviderSnapshotDoesNotInventTiersOrRemovePriceSpecificStock() {
        expectTierPrices("""
                {"187":{"wa":{"0.1234567890123456789":9223372036854775807,"0.00000001":3}}}
                """);
        expectProviders("""
                {"187":{"wa":{"price":0.01,"count":1,"providers":{
                    "4":{"count":1,"price":[0.99,1E-8,0.1234567890123456789],"provider_id":4}
                }}}}
                """);

        var tiers = client.getPriceTiers("wa", "187");

        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).cost()).isEqualByComparingTo("0.00000001");
        assertThat(tiers.get(0).count()).isEqualTo(3);
        assertThat(tiers.get(1).cost()).isEqualByComparingTo("0.1234567890123456789");
        assertThat(tiers.get(1).count()).isEqualTo(Long.MAX_VALUE);
        assertThat(tiers.get(1).providerIds()).containsExactly("4");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"187\":{}}", "{\"187\":{\"wa\":{}}}"})
    void emptyTierSnapshotDoesNotRequestProviderDetails(String response) {
        expectTierPrices(response);

        assertThat(client.getPriceTiers("wa", "187")).isEmpty();

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"187\":{}}", "{\"187\":{\"wa\":{\"price\":0,\"count\":0,\"providers\":{}}}}"})
    void missingProviderSnapshotKeepsTierStockWithoutClaimingProviderAvailability(String response) {
        expectTierPrices("{\"187\":{\"wa\":{\"0.35\":12}}}");
        expectProviders(response);

        assertThat(client.getPriceTiers("wa", "187")).singleElement().satisfies(tier -> {
            assertThat(tier.count()).isEqualTo(12);
            assertThat(tier.providerIds()).isEmpty();
        });
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]", "null", "{\"12\":{\"wa\":{\"0.35\":1}}}",
            "{\"187\":{\"tg\":{\"0.35\":1}}}", "{\"187\":{\"wa\":[]}}",
            "{\"187\":{\"wa\":{\"-0.35\":1}}}", "{\"187\":{\"wa\":{\"0.35\":-1}}}",
            "{\"187\":{\"wa\":{\"0.35\":1.5}}}", "{\"187\":{\"wa\":{\"0.35\":null}}}",
            "{\"187\":{\"wa\":{\"0.35\":9223372036854775808}}}",
            "{\"187\":{\"wa\":{\"0.35\":1,\"0.350\":2}}}",
            "{\"187\":{\"wa\":{\"0.35\":1,\"0.35\":2}}}",
            "{\"187\":{\"wa\":{\"0.35\":1}}}{}"
    })
    void malformedTierSnapshotFailsWithoutRequestingProviderData(String response) {
        expectTierPrices(response);

        assertInvalidResponse();

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "{\"12\":{\"wa\":{}}}", "{\"187\":{\"tg\":{}}}",
            "{\"187\":{\"wa\":{\"price\":0.35,\"count\":12}}}",
            "{\"187\":{\"wa\":{\"price\":0.35,\"count\":12,\"providers\":[]}}}",
            "{\"187\":{\"wa\":{\"price\":0.35,\"count\":12,\"providers\":{\"4\":null}}}}"
    })
    void malformedProviderSnapshotDoesNotReturnAnApparentlyPurchasableResult(String response) {
        expectTierPrices("{\"187\":{\"wa\":{\"0.35\":12}}}");
        expectProviders(response);

        assertInvalidResponse();

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"count\":12,\"price\":0.35,\"provider_id\":4}",
            "{\"count\":12,\"price\":[-0.35],\"provider_id\":4}",
            "{\"count\":12,\"price\":[null],\"provider_id\":4}",
            "{\"count\":-1,\"price\":[0.35],\"provider_id\":4}",
            "{\"count\":12,\"price\":[0.35],\"provider_id\":5}",
            "{\"count\":12,\"price\":[0.35],\"provider_id\":\"4&api_key=raw-secret\"}"
    })
    void invalidProviderEntryFailsClosedAndDoesNotExposeRawResponse(String provider) {
        expectTierPrices("{\"187\":{\"wa\":{\"0.35\":12}}}");
        expectProviders("{\"187\":{\"wa\":{\"price\":0.35,\"count\":12,\"providers\":{\"4\":" + provider + "}}}}");

        assertInvalidResponse();

        server.verify();
    }

    @Test
    void zeroStockProvidersAreNotSuggestedForThePriceTier() {
        expectTierPrices("{\"187\":{\"wa\":{\"0.35\":12}}}");
        expectProviders("""
                {"187":{"wa":{"price":0.35,"count":0,"providers":{
                    "4":{"count":0,"price":[0.35],"provider_id":4}
                }}}}
                """);

        assertThat(client.getPriceTiers("wa", "187")).singleElement()
                .satisfies(tier -> assertThat(tier.providerIds()).isEmpty());
        server.verify();
    }

    @Test
    void selectedTierCanBeSentAsEqualMinimumMaximumPriceAndProviderFilter() {
        properties.setPurchasesEnabled(true);
        server.expect(queryParam("action", "getNumberV2"))
                .andExpect(queryParam("minPrice", "0.35"))
                .andExpect(queryParam("maxPrice", "0.35"))
                .andExpect(queryParam("providerIds", "4"))
                .andRespond(withSuccess("""
                        {"activationId":123,"phoneNumber":"18036181752","activationCost":0.35,"currency":643}
                        """, MediaType.APPLICATION_JSON));

        var options = new GrizzlyNumberRequest.Options(new BigDecimal("0.35"), List.of("4"), List.of(), List.of());
        var activation = client.acquireNumber(new GrizzlyNumberRequest("wa", "187", new BigDecimal("0.35"), options));

        assertThat(activation.cost()).isEqualByComparingTo("0.35");
        assertThat(activation.currency()).isEqualTo(643);
        server.verify();
    }

    @Test
    void tierQueryValidatesCountryAndServiceBeforeAnyHttpRequest() {
        assertThatThrownBy(() -> client.getPriceTiers("wa&action=getNumberV2", "187"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> client.getPriceTiers("wa", "any")).isInstanceOf(BusinessException.class);
        server.verify();
    }

    private void expectTierPrices(String response) {
        server.expect(queryParam("action", "getPricesV2"))
                .andExpect(queryParam("country", "187"))
                .andExpect(queryParam("service", "wa"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void expectProviders(String response) {
        server.expect(queryParam("action", "getPricesV3"))
                .andExpect(queryParam("country", "187"))
                .andExpect(queryParam("service", "wa"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void assertInvalidResponse() {
        assertThatThrownBy(() -> client.getPriceTiers("wa", "187"))
                .isInstanceOfSatisfying(GrizzlySmsException.class, exception -> {
                    assertThat(exception.getReason()).isEqualTo(GrizzlySmsFailure.INVALID_RESPONSE);
                    assertThat(exception.isOutcomeUnknown()).isFalse();
                    assertThat(exception.getMessage()).doesNotContain("raw-secret", "test-only-key");
                    assertThat(exception.getCause()).isNull();
                });
    }
}
