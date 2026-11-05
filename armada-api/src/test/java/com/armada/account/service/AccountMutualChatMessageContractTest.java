package com.armada.account.service;

import com.armada.platform.kafka.consumer.message.ProtocolMessageEventConsumer;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.protocol.risk.ProtocolRiskEventSink;
import com.armada.platform.protocol.risk.ProtocolRiskResultMetadata;
import com.armada.marketing.service.impl.MarketingSendResultServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 真消费者解析及真实业务 Sink 路由，不启动 Kafka。 */
class AccountMutualChatMessageContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AccountMutualChatTransactions transactions = mock(AccountMutualChatTransactions.class);
    private final AccountMutualChatResultSink sink = new AccountMutualChatResultSink(transactions);
    private final ProtocolRiskEventSink risk = mock(ProtocolRiskEventSink.class);
    private final ProtocolMessageEventConsumer consumer = new ProtocolMessageEventConsumer(json,
            List.of(sink, new MarketingSendResultServiceImpl(null, null, null, null)), List.of(sink), risk);

    @Test void parsesMutualResultWithoutMarketingFieldsAndRoutesExactlyOnce() {
        consumer.onMessage(result().toString(), null);
        var event = ArgumentCaptor.forClass(ProtocolMessageSendResultReportedEvent.class);
        verify(transactions).apply(event.capture());
        assertThat(event.getValue().mutualChatTaskId()).isEqualTo(81);
        assertThat(event.getValue().mutualChatItemId()).isEqualTo(92);
        assertThat(event.getValue().accountId()).isEqualTo(11);
        assertThat(event.getValue().marketingTaskId()).isNull();
        var metadata = ArgumentCaptor.forClass(ProtocolRiskResultMetadata.class);
        verify(risk).handleResult(metadata.capture());
        assertThat(metadata.getValue().correlation().businessId()).isEqualTo(81);
        assertThat(metadata.getValue().correlation().businessItemId()).isEqualTo(92);
    }

    @Test void rejectsMissingMutualIdentifiersBeforeBusinessWrite() {
        for (String key : List.of("mutualChatTaskId", "mutualChatItemId", "accountId")) {
            var event = result(); ((com.fasterxml.jackson.databind.node.ObjectNode)event.get("data")).remove(key);
            assertThatThrownBy(() -> consumer.onMessage(event.toString(), null)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(transactions);
    }

    @Test void consumesMutualAckWithoutChangingBusinessStateOrKafkaRetry() {
        var event = result().put("event", "message.ack");
        ((com.fasterxml.jackson.databind.node.ObjectNode)event.get("data")).put("ackStatus", "DELIVERED");
        consumer.onMessage(event.toString(), null);
        verifyNoInteractions(transactions);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode result() {
        var envelope = json.createObjectNode().put("event", "message.send_result_reported").put("eventId", "chat-result");
        envelope.putObject("data").put("tenantId", 7).put("accountId", 11).put("protocolAccountId", "acc_11")
                .put("source", AccountMutualChatPolicy.SOURCE).put("mutualChatTaskId", 81).put("mutualChatItemId", 92)
                .put("commandId", "cmd_chat").put("jid", "19990000002@s.whatsapp.net").put("targetKind", "PRIVATE")
                .put("success", true).put("messageId", "wamid.chat");
        return envelope;
    }
}
