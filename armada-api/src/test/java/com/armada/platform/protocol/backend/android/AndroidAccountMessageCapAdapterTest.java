package com.armada.platform.protocol.backend.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolMessageCap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AndroidAccountMessageCapAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private AndroidNativeClient client;

    @Test
    void mapsEveryFieldIncludingSubscriptionAndKeepsSentinelQuota() throws Exception {
        when(client.messageCap("919000000001", true)).thenReturn(envelope(0, node("""
                {"resultStatus":"NON_NUMERIC_QUOTA","totalQuota":"-1","usedQuota":"0","remaining":null,
                 "cappingStatus":"NONE","cycleStart":"1793509200","cycleEnd":"1796101199",
                 "serverSentTimestamp":"1793883238","mvStatus":"NOT_ELIGIBLE","oteStatus":"NOT_ELIGIBLE",
                 "subscriptionStatus":{"status":"ACTIVE","name":"MP4B"},"extensions":{"k":1},
                 "fetchedAt":"2026-11-05T12:53:58Z","debug":{"format":"ARGO","rawHex":"04"}}"""), null));

        ProtocolMessageCap cap = adapter().query(account(), true);

        assertThat(cap.resultStatus()).isEqualTo("NON_NUMERIC_QUOTA");
        assertThat(cap.totalQuota()).isEqualTo("-1");
        assertThat(cap.usedQuota()).isEqualTo("0");
        assertThat(cap.remaining()).isNull();
        assertThat(cap.cappingStatus()).isEqualTo("NONE");
        assertThat(cap.cycleStart()).isEqualTo("1793509200");
        assertThat(cap.cycleEnd()).isEqualTo("1796101199");
        assertThat(cap.serverSentTimestamp()).isEqualTo("1793883238");
        assertThat(cap.mvStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(cap.oteStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(cap.subscriptionStatus()).isEqualTo("ACTIVE");
        assertThat(cap.subscriptionName()).isEqualTo("MP4B");
        assertThat(cap.extensions().path("k").asInt()).isEqualTo(1);
        assertThat(cap.debug().path("rawHex").asText()).isEqualTo("04");
        assertThat(cap.failure()).isNull();
    }

    @Test
    void absentSubscriptionStaysEmpty() throws Exception {
        when(client.messageCap("919000000001", false)).thenReturn(envelope(0,
                node("{\"resultStatus\":\"NO_DATA\",\"subscriptionStatus\":null}"), null));

        ProtocolMessageCap cap = adapter().query(account(), false);

        assertThat(cap.subscriptionStatus()).isNull();
        assertThat(cap.subscriptionName()).isNull();
        assertThat(cap.extensions()).isNull();
        assertThat(cap.debug()).isNull();
    }

    @Test
    void platformRejectionWithStageBecomesRejectedResultNotException() throws Exception {
        JsonNode failure = node("{\"stage\":\"INVALID_NUMBER\",\"field\":\"total_quota\",\"numericValue\":\"-2\","
                + "\"upstreamErrors\":[{\"message\":\"x\"}],\"debug\":{\"rawHex\":\"04\"}}");
        when(client.messageCap("919000000001", true)).thenReturn(envelope(1003, failure, "quota response rejected"));

        ProtocolMessageCap cap = adapter().query(account(), true);

        assertThat(cap.resultStatus()).isEqualTo(ProtocolMessageCap.REJECTED);
        assertThat(cap.totalQuota()).isNull();
        assertThat(cap.failure().path("stage").asText()).isEqualTo("INVALID_NUMBER");
        assertThat(cap.debug().path("rawHex").asText()).isEqualTo("04");
    }

    @Test
    void offlineCodeMapsToAccountNotOnlineWithContext() {
        when(client.messageCap("919000000001", false))
                .thenReturn(envelope(1010, NullNode.getInstance(), "quota query requires the existing online account session"));

        assertThatThrownBy(() -> adapter().query(account(), false))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.ACCOUNT_NOT_ONLINE);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("account.messageCap");
                });
    }

    @Test
    void unknownFailureWithoutStageStaysProtocolException() {
        when(client.messageCap("919000000001", false))
                .thenReturn(envelope(1003, NullNode.getInstance(), "quota query cancelled"));

        assertThatThrownBy(() -> adapter().query(account(), false))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNKNOWN));
    }

    @Test
    void successWithoutResultStatusIsUnrecognized() throws Exception {
        when(client.messageCap("919000000001", false)).thenReturn(envelope(0, node("{\"totalQuota\":\"5\"}"), null));

        assertThatThrownBy(() -> adapter().query(account(), false))
                .isInstanceOfSatisfying(ProtocolException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED));
    }

    @Test
    void transportFailureKeepsCodeAndAddsContext() {
        when(client.messageCap("919000000001", false))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));

        assertThatThrownBy(() -> adapter().query(account(), false))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.TIMEOUT);
                    assertThat(ex.operationId()).contains("account:1");
                });
    }

    private AndroidAccountMessageCapAdapter adapter() {
        return new AndroidAccountMessageCapAdapter(
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
