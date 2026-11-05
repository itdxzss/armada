package com.armada.account.service;

import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckSink;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import org.springframework.stereotype.Component;

/** 互聊只结算发送结果；接住 Web ACK，避免无处理器触发 Kafka 重试。 */
@Component
public class AccountMutualChatResultSink implements ProtocolMessageSendResultReportedSink, ProtocolMessageAckSink {
    private final AccountMutualChatTransactions transactions;
    /** 状态更新由独立事务服务执行。 */
    public AccountMutualChatResultSink(AccountMutualChatTransactions transactions) {
        this.transactions = transactions;
    }
    @Override
    public boolean supports(ProtocolMessageSendResultReportedEvent event) {
        return event != null && AccountMutualChatPolicy.SOURCE.equals(event.source());
    }
    @Override
    public boolean supports(ProtocolMessageAckEvent event) {
        return event != null && AccountMutualChatPolicy.SOURCE.equals(event.source());
    }
    @Override
    public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        if (supports(event)) transactions.apply(event);
    }
    /** 首版不追踪送达或已读，ACK 不修改发送事实。 */
    @Override
    public void handleAck(ProtocolMessageAckEvent event) {
        // 协议会对互聊消息发布 ACK；明确消费但不推断或改写业务状态。
    }
}
