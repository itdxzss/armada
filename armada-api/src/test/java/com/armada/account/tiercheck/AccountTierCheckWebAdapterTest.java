package com.armada.account.tiercheck;

import com.armada.platform.protocol.backend.web.WebAccountIntegrityAdapter;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import com.armada.platform.protocol.routing.RoutingAccountIntegrityPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** RestClient HTTP 替身拦截全部请求，不连接真实协议层。 */
class AccountTierCheckWebAdapterTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://fixture.invalid");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final WebAccountIntegrityAdapter adapter = new WebAccountIntegrityAdapter(new ProtocolHttpExecutor(builder.build()));
    private final ProtocolAccountRef account = new ProtocolAccountRef(1L, ProtocolBackend.WEB, "fixture_1", "00000000001");

    @Test void readsOnlyNormalizedFieldsAndUsesContractPath() {
        server.expect(requestTo("http://fixture.invalid/v1/accounts/fixture_1/integrity-query"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"phone\":\"00000000002\"}"))
                .andRespond(withSuccess("""
                    {"status":"OK","userType":"BUSINESS","trustTier":"TIER_1","isSuspicious":false,
                    "isBanned":false,"isMvSubscribed":true,"joinDateMs":1234,"phoneCountryCode":"ZZ","raw":"ignored"}
                    """, MediaType.APPLICATION_JSON));
        var r = adapter.query(account, "00000000002");
        assertThat(r.status()).isEqualTo(ProtocolAccountIntegrity.Status.OK);
        assertThat(r.trustTier()).isEqualTo("TIER_1"); assertThat(r.isSuspicious()).isFalse(); assertThat(r.joinDateMs()).isEqualTo(1234);
        server.verify();
    }
    @ParameterizedTest @CsvSource({"EMPTY,EMPTY,EMPTY_RESPONSE", "NOT_ALLOWED,NOT_ALLOWED,NOT_ALLOWED", "ERROR,ERROR,TIMEOUT", "BOGUS,ERROR,INVALID_RESPONSE"})
    void classifiesStatusesWithoutCopyingFieldsOnFailure(String status, String expected, String reason) {
        server.expect(requestTo("http://fixture.invalid/v1/accounts/fixture_1/integrity-query"))
                .andRespond(withSuccess("{\"status\":\"" + status + "\",\"reasonCode\":\"TIMEOUT\",\"trustTier\":\"TIER_3\"}", MediaType.APPLICATION_JSON));
        var result = adapter.query(account, "00000000002");
        assertThat(result.status().name()).isEqualTo(expected); assertThat(result.reasonCode()).isEqualTo(reason);
        assertThat(result.trustTier()).isNull(); server.verify();
    }
    @Test void malformedSuccessAndUnknownReasonAreSafe() {
        server.expect(requestTo("http://fixture.invalid/v1/accounts/fixture_1/integrity-query"))
                .andRespond(withSuccess("{\"status\":\"OK\"}", MediaType.APPLICATION_JSON));
        assertThat(adapter.query(account, "00000000002").reasonCode()).isEqualTo("INVALID_RESPONSE"); server.verify();
    }
    @Test void offlineUsesExistingErrorCodeWithoutRawResponse() {
        server.expect(requestTo("http://fixture.invalid/v1/accounts/fixture_1/integrity-query"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ACCOUNT_UNAVAILABLE\",\"message\":\"PRIVATE_RAW_MARKER\"}"));
        assertThatThrownBy(() -> adapter.query(account, "00000000002")).isInstanceOfSatisfying(ProtocolException.class, ex -> {
            assertThat(ex.getMessage()).doesNotContain("PRIVATE_RAW_MARKER"); assertThat(ex.getCause()).isNull();
        }); server.verify();
    }
    @Test void followsExplicitOwnerOnceWithoutRetryingTimeouts() {
        server.expect(requestTo("http://fixture.invalid/v1/accounts/fixture_1/integrity-query"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_OWNER\",\"details\":{\"ownerEndpoint\":\"http://owner.fixture.invalid\"}}"));
        server.expect(requestTo("http://owner.fixture.invalid/v1/accounts/fixture_1/integrity-query"))
                .andRespond(withSuccess("{\"status\":\"ERROR\",\"reasonCode\":\"TIMEOUT\"}", MediaType.APPLICATION_JSON));
        assertThat(adapter.query(account, "00000000002").reasonCode()).isEqualTo("TIMEOUT"); server.verify();
    }
    @Test void androidIsUnsupportedWithoutAnyHttpRequest() {
        var routing = new RoutingAccountIntegrityPort(List.of(adapter));
        assertThat(routing.supports(ProtocolBackend.WEB)).isTrue(); assertThat(routing.supports(ProtocolBackend.ANDROID)).isFalse();
        assertThatThrownBy(() -> routing.query(new ProtocolAccountRef(2L, ProtocolBackend.ANDROID, "fixture_2", "00000000002"), "00000000001"))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_BACKEND));
        server.verify();
    }
}
