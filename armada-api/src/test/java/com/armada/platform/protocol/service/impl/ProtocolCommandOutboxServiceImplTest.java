package com.armada.platform.protocol.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.kafka.outbox.ProtocolCommandDispatchTrigger;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * 协议命令 Outbox enqueue service 单测。
 *
 * <p>Slice 3 只验证上线命令如何转成 outbox row。不发送 Kafka,不接账号上线入口。</p>
 */
class ProtocolCommandOutboxServiceImplTest {

    private final ProtocolCommandOutboxMapper mapper = org.mockito.Mockito.mock(ProtocolCommandOutboxMapper.class);
    private final ProtocolCommandDispatchTrigger dispatchTrigger =
            org.mockito.Mockito.mock(ProtocolCommandDispatchTrigger.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enqueueOnlineCommands_singleCommand_insertsPendingRowWithStableEnvelopeAndSafePayload() throws Exception {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-single"), List.of());
        ProtocolOnlineCommandRequest command = onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L);
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueOnlineCommands(List.of(command));

        assertThat(result.batchId()).isNull();
        assertThat(result.commandIds()).containsExactly("cmd-single");
        assertThat(result.inserted()).isEqualTo(1);

        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).hasSize(1);
        ProtocolCommandOutbox row = rows.get(0);
        assertThat(row.getCommandId()).isEqualTo("cmd-single");
        assertThat(row.getBatchId()).isNull();
        assertThat(row.getCommandType()).isEqualTo("account.online.requested");
        assertThat(row.getAggregateType()).isEqualTo("ACCOUNT");
        assertThat(row.getAggregateId()).isEqualTo(100L);
        assertThat(row.getKafkaTopic()).isEqualTo("protocol.account.commands.v1");
        assertThat(row.getKafkaKey()).isEqualTo("acc_100");
        assertThat(row.getProtocolAccountId()).isEqualTo("acc_100");
        assertThat(row.getStatus()).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(row.getRetryCount()).isZero();
        assertThat(row.getNextRetryAt()).isZero();
        assertThat(row.getCreatedAt()).isEqualTo(row.getUpdatedAt());
        assertThat(row.getCreatedAt()).isPositive();

        Map<String, Object> payload = objectMapper.readValue(row.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("accountId", 100)
                .containsEntry("protocolAccountId", "acc_100")
                .containsEntry("credentialFormat", "BAILEYS_JSON")
                .containsEntry("proxyId", 7)
                .containsEntry("source", "manual_online");
        assertThat(row.getPayloadJson())
                .doesNotContain("credentialJson")
                .doesNotContain("creds")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("proxyHost");
        verify(dispatchTrigger).dispatchAfterCommit(rows);
    }

    @Test
    void enqueueOnlineCommands_batch500_usesOneBatchIdAndOneRowPerCommand() {
        List<String> commandIds = java.util.stream.IntStream.rangeClosed(1, 500)
                .mapToObj(i -> "cmd-" + i)
                .toList();
        TestableProtocolCommandOutboxService service = newService(commandIds, List.of("batch-1"));
        List<ProtocolOnlineCommandRequest> commands = java.util.stream.IntStream.rangeClosed(1, 500)
                .mapToObj(i -> onlineCommand((long) i, "acc_" + i, CredentialFormat.PARAMS, 7000L + i))
                .toList();
        when(mapper.batchInsertPending(anyList())).thenReturn(500);

        ProtocolCommandOutboxEnqueueResult result = service.enqueueOnlineCommands(commands);

        assertThat(result.batchId()).isEqualTo("batch-1");
        assertThat(result.commandIds()).containsExactlyElementsOf(commandIds);
        assertThat(result.inserted()).isEqualTo(500);
        List<ProtocolCommandOutbox> rows = capturedRows();
        assertThat(rows).hasSize(500);
        assertThat(rows).allSatisfy(row -> assertThat(row.getBatchId()).isEqualTo("batch-1"));
        assertThat(rows).extracting(ProtocolCommandOutbox::getCommandId)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(commandIds);
        verify(dispatchTrigger).dispatchAfterCommit(rows);
    }

    @Test
    void enqueueOnlineCommands_duplicateGeneratedCommandId_throwsConflictBeforeMapperInsert() {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-dupe", "cmd-dupe"), List.of("batch-1"));
        List<ProtocolOnlineCommandRequest> commands = List.of(
                onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L),
                onlineCommand(101L, "acc_101", CredentialFormat.BAILEYS_JSON, 8L));

        assertThatThrownBy(() -> service.enqueueOnlineCommands(commands))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协议命令 ID 重复")
                .extracting("code")
                .isEqualTo(ErrorCode.CONFLICT.code());
        verify(mapper, never()).batchInsertPending(anyList());
        verify(dispatchTrigger, never()).dispatchAfterCommit(anyList());
    }

    @Test
    void enqueueOnlineCommands_mapperDuplicateCommandId_mapsToBusinessConflict() {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-existing"), List.of());
        when(mapper.batchInsertPending(anyList())).thenThrow(new DuplicateKeyException("uk_command_id"));

        assertThatThrownBy(() -> service.enqueueOnlineCommands(List.of(
                onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协议命令 ID 已存在")
                .extracting("code")
                .isEqualTo(ErrorCode.CONFLICT.code());
        verify(dispatchTrigger, never()).dispatchAfterCommit(anyList());
    }

    @Test
    void enqueueOnlineCommands_insertedCountMismatch_throwsConflictBeforeDispatch() {
        TestableProtocolCommandOutboxService service = newService(List.of("cmd-a", "cmd-b"), List.of("batch-1"));
        when(mapper.batchInsertPending(anyList())).thenReturn(1);

        assertThatThrownBy(() -> service.enqueueOnlineCommands(List.of(
                onlineCommand(100L, "acc_100", CredentialFormat.BAILEYS_JSON, 7L),
                onlineCommand(101L, "acc_101", CredentialFormat.PARAMS, 8L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("协议命令 outbox 写入数量不一致")
                .extracting("code")
                .isEqualTo(ErrorCode.CONFLICT.code());
        verify(dispatchTrigger, never()).dispatchAfterCommit(anyList());
    }

    private TestableProtocolCommandOutboxService newService(List<String> commandIds, List<String> batchIds) {
        return new TestableProtocolCommandOutboxService(mapper, objectMapper, dispatchTrigger, commandIds, batchIds);
    }

    private List<ProtocolCommandOutbox> capturedRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolCommandOutbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).batchInsertPending(captor.capture());
        return captor.getValue();
    }

    private static ProtocolOnlineCommandRequest onlineCommand(Long accountId,
                                                              String protocolAccountId,
                                                              CredentialFormat credentialFormat,
                                                              Long proxyId) {
        return new ProtocolOnlineCommandRequest(
                accountId,
                protocolAccountId,
                credentialFormat,
                proxyId,
                "manual_online");
    }

    private static final class TestableProtocolCommandOutboxService extends ProtocolCommandOutboxServiceImpl {

        private final ArrayDeque<String> commandIds;
        private final ArrayDeque<String> batchIds;

        private TestableProtocolCommandOutboxService(ProtocolCommandOutboxMapper mapper,
                                                     ObjectMapper objectMapper,
                                                     ProtocolCommandDispatchTrigger dispatchTrigger,
                                                     List<String> commandIds,
                                                     List<String> batchIds) {
            super(mapper, objectMapper, dispatchTrigger);
            this.commandIds = new ArrayDeque<>(commandIds);
            this.batchIds = new ArrayDeque<>(batchIds);
        }

        @Override
        protected String newCommandId() {
            return commandIds.removeFirst();
        }

        @Override
        protected String newBatchId() {
            return batchIds.removeFirst();
        }
    }
}
