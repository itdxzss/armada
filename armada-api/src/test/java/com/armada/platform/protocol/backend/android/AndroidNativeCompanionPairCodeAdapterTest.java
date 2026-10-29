package com.armada.platform.protocol.backend.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

/** 安卓层配对码输入的成功、可重试和不可重试原因。 */
class AndroidNativeCompanionPairCodeAdapterTest {

    private final AndroidNativeClient client = mock(AndroidNativeClient.class);
    private final AndroidNativeCompanionPairCodeAdapter adapter =
            new AndroidNativeCompanionPairCodeAdapter(client, new AndroidResponseDecoder());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptedWhenAndroidSentPrimaryHello() {
        when(client.pairCodeInput("628123460001", "ABCDEFGH")).thenReturn(new AndroidResponseEnvelope(0,
                mapper.createObjectNode().put("refSummary", "3@2:x"), new TextNode("primary_hello 已发送"), null));
        var result = adapter.submit("628123460001", "ABCDEFGH");
        assertThat(result.accepted()).isTrue();
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void reasonsComeFromDataReasonAndOnlyNoPendingIsRetryable() {
        when(client.pairCodeInput("628123460001", "ABCDEFGH")).thenReturn(failure("NO_PENDING"));
        assertThat(adapter.submit("628123460001", "ABCDEFGH").retryable()).isTrue();

        when(client.pairCodeInput("628123460001", "ABCDEFGH")).thenReturn(failure("AMBIGUOUS"));
        var ambiguous = adapter.submit("628123460001", "ABCDEFGH");
        assertThat(ambiguous.reason()).isEqualTo("AMBIGUOUS");
        assertThat(ambiguous.retryable()).isFalse();

        when(client.pairCodeInput("628123460001", "ABCDEFGH")).thenReturn(failure("something new"));
        assertThat(adapter.submit("628123460001", "ABCDEFGH").reason()).isEqualTo("UNKNOWN");
    }

    private AndroidResponseEnvelope failure(String reason) {
        return new AndroidResponseEnvelope(1004, mapper.createObjectNode().put("reason", reason),
                new TextNode("pair code input failed"), null);
    }
}
