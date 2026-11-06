package com.armada.platform.protocol.backend.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import com.armada.platform.protocol.routing.RoutingAccountIntegrityPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AndroidAccountIntegrityAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET = "919000000002";

    @Mock
    private AndroidNativeClient client;

    @Test
    void mapsOkFieldsAndNeverRequestsDebug() throws Exception {
        when(client.accountIntegrity("919000000001", TARGET, false)).thenReturn(envelope(0, node("""
                {"status":"OK","userType":"BUSINESS","trustTier":"TIER_1","isSuspicious":true,"isBanned":false,
                 "isMvSubscribed":false,"joinDateMs":1700000000000,"phoneCountryCode":"PK","queryId":"8370845569632382",
                 "debug":{"rawHex":"04"}}"""), null));

        ProtocolAccountIntegrity result = adapter().query(account(), TARGET);

        assertThat(result.status()).isEqualTo(ProtocolAccountIntegrity.Status.OK);
        assertThat(result.userType()).isEqualTo("BUSINESS");
        assertThat(result.trustTier()).isEqualTo("TIER_1");
        assertThat(result.isSuspicious()).isTrue();
        assertThat(result.isBanned()).isFalse();
        assertThat(result.isMvSubscribed()).isFalse();
        assertThat(result.joinDateMs()).isEqualTo(1700000000000L);
        assertThat(result.phoneCountryCode()).isEqualTo("PK");
        assertThat(result.reasonCode()).isNull();
        verify(client, never()).accountIntegrity("919000000001", TARGET, true);
    }

    @Test
    void emptyNotAllowedAndErrorStatusesKeepFixedReasons() throws Exception {
        when(client.accountIntegrity("919000000001", TARGET, false))
                .thenReturn(envelope(0, node("{\"status\":\"EMPTY\"}"), null))
                .thenReturn(envelope(0, node("{\"status\":\"NOT_ALLOWED\"}"), null))
                .thenReturn(envelope(0, node("{\"status\":\"ERROR\",\"reasonCode\":\"UNSUPPORTED_FORMAT\"}"), null))
                .thenReturn(envelope(0, node("{\"status\":\"ERROR\",\"reasonCode\":\"secret raw text\"}"), null));

        assertThat(adapter().query(account(), TARGET).status()).isEqualTo(ProtocolAccountIntegrity.Status.EMPTY);
        assertThat(adapter().query(account(), TARGET).status()).isEqualTo(ProtocolAccountIntegrity.Status.NOT_ALLOWED);
        assertThat(adapter().query(account(), TARGET).reasonCode()).isEqualTo("UNSUPPORTED_FORMAT");
        ProtocolAccountIntegrity unknown = adapter().query(account(), TARGET);
        assertThat(unknown.status()).isEqualTo(ProtocolAccountIntegrity.Status.ERROR);
        assertThat(unknown.reasonCode()).isEqualTo("PROTOCOL_ERROR");
    }

    @Test
    void okWithoutAnyFieldOrBadTierIsInvalidResponse() throws Exception {
        when(client.accountIntegrity("919000000001", TARGET, false))
                .thenReturn(envelope(0, node("{\"status\":\"OK\"}"), null))
                .thenReturn(envelope(0, node("{\"status\":\"OK\",\"trustTier\":\"bad tier!\"}"), null))
                .thenReturn(envelope(0, node("{\"trustTier\":\"TIER_1\"}"), null));

        for (int i = 0; i < 3; i++) {
            ProtocolAccountIntegrity result = adapter().query(account(), TARGET);
            assertThat(result.status()).isEqualTo(ProtocolAccountIntegrity.Status.ERROR);
            assertThat(result.reasonCode()).isEqualTo("INVALID_RESPONSE");
        }
    }

    @Test
    void offlineCodeMapsToAccountNotOnlineWithoutLeakingMessage() {
        when(client.accountIntegrity("919000000001", TARGET, false))
                .thenReturn(envelope(1010, NullNode.getInstance(), "raw upstream detail"));

        assertThatThrownBy(() -> adapter().query(account(), TARGET))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.ACCOUNT_NOT_ONLINE);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("account.integrity");
                    assertThat(ex.getMessage()).doesNotContain("raw upstream detail");
                });
    }

    @Test
    void transportFailureKeepsCodeAndDropsOriginalMessage() {
        when(client.accountIntegrity("919000000001", TARGET, false))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "body preview with secrets"));

        assertThatThrownBy(() -> adapter().query(account(), TARGET))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.TIMEOUT);
                    assertThat(ex.operationId()).contains("account:1");
                    assertThat(ex.getMessage()).doesNotContain("secrets");
                });
    }

    @Test
    void routingSupportsAndroidWhenRegistered() throws Exception {
        when(client.accountIntegrity("919000000001", TARGET, false))
                .thenReturn(envelope(0, node("{\"status\":\"OK\",\"trustTier\":\"TIER_2\"}"), null));
        var routing = new RoutingAccountIntegrityPort(List.of(adapter()));

        assertThat(routing.supports(ProtocolBackend.ANDROID)).isTrue();
        assertThat(routing.supports(ProtocolBackend.WEB)).isFalse();
        assertThat(routing.query(account(), TARGET).trustTier()).isEqualTo("TIER_2");
    }

    private AndroidAccountIntegrityAdapter adapter() {
        return new AndroidAccountIntegrityAdapter(
                client, new AndroidResponseDecoder(), new AndroidGroupJoinErrorMapper());
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(1L, ProtocolBackend.ANDROID, "acc_919000000001", "919000000001");
    }

    private static JsonNode node(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static AndroidResponseEnvelope envelope(Integer code, JsonNode data, String message) {
        return new AndroidResponseEnvelope(code, data, message == null ? null : TextNode.valueOf(message), null);
    }
}
