package com.armada.platform.protocol.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.testsupport.DbTestBase;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 协议命令 Outbox Mapper 真库测试。
 *
 * <p>只覆盖 Slice 2 的持久层能力:批量写入 pending 命令、按重试时间扫描待发送命令、
 * 抢占锁定以及发送/重试/死信状态流转。不接入 Kafka producer,也不改账号上线入口。</p>
 */
class ProtocolCommandOutboxMapperDbTest extends DbTestBase {

    private static final int TEST_SCAN_LIMIT = 10_000;

    @Autowired
    private ProtocolCommandOutboxMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void batchInsertPendingAndSelectDispatchable_returnsOnlyDuePendingRows() throws Exception {
        long now = System.currentTimeMillis();
        ProtocolCommandOutbox due = pendingCommand("due-" + now, "batch-" + now, 1001L, now);
        due.setNextRetryAt(now - 1);
        ProtocolCommandOutbox future = pendingCommand("future-" + now, "batch-" + now, 1002L, now);
        future.setNextRetryAt(now + 60_000);

        int inserted = mapper.batchInsertPending(List.of(due, future));

        assertThat(inserted).isEqualTo(2);
        List<ProtocolCommandOutbox> rows = mapper.selectDispatchable(
                ProtocolCommandOutboxStatus.PENDING.code(), now, TEST_SCAN_LIMIT);
        assertThat(rows).extracting(ProtocolCommandOutbox::getCommandId)
                .contains(due.getCommandId())
                .doesNotContain(future.getCommandId());

        ProtocolCommandOutbox found = findByCommandId(rows, due.getCommandId());
        assertThat(found.getTenantId()).isEqualTo(TEST_TENANT_ID);
        assertThat(found.getBatchId()).isEqualTo(due.getBatchId());
        assertThat(found.getAggregateType()).isEqualTo("ACCOUNT");
        assertThat(found.getAggregateId()).isEqualTo(1001L);
        assertThat(found.getKafkaTopic()).isEqualTo("protocol.account.commands.v1");
        assertThat(found.getKafkaKey()).isEqualTo("acc_1001");
        assertThat(found.getProtocolAccountId()).isEqualTo("acc_1001");
        Map<String, Long> payload = objectMapper.readValue(found.getPayloadJson(), new TypeReference<>() {
        });
        assertThat(payload).containsEntry("accountId", 1001L)
                .containsEntry("proxyId", 7001L);
        assertThat(found.getStatus()).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(found.getRetryCount()).isZero();
    }

    @Test
    void lockAndSentTransitions_requireExpectedPreviousStatus() {
        long now = System.currentTimeMillis();
        ProtocolCommandOutbox row = pendingCommand("sent-" + now, null, 2001L, now);
        mapper.batchInsertPending(List.of(row));
        Long id = insertedId(row.getCommandId(), now);

        int locked = mapper.markLocked(List.of(id), "publisher-a", now + 1);
        int lockedAgain = mapper.markLocked(List.of(id), "publisher-b", now + 2);

        assertThat(locked).isEqualTo(1);
        assertThat(lockedAgain).isZero();
        OutboxState lockedState = state(id);
        assertThat(lockedState.status()).isEqualTo(ProtocolCommandOutboxStatus.LOCKED.code());
        assertThat(lockedState.lockedBy()).isEqualTo("publisher-a");
        assertThat(lockedState.lockedAt()).isEqualTo(now + 1);

        int sent = mapper.markSent(id, now + 3);
        int sentAgain = mapper.markSent(id, now + 4);

        assertThat(sent).isEqualTo(1);
        assertThat(sentAgain).isZero();
        OutboxState sentState = state(id);
        assertThat(sentState.status()).isEqualTo(ProtocolCommandOutboxStatus.SENT.code());
        assertThat(sentState.sentAt()).isEqualTo(now + 3);
        assertThat(sentState.updatedAt()).isEqualTo(now + 3);
        assertThat(sentState.lastError()).isNull();
    }

    @Test
    void retryAndDeadTransitions_releaseLockedRowsAndRecordFailureReason() {
        long now = System.currentTimeMillis();
        ProtocolCommandOutbox retry = pendingCommand("retry-" + now, "batch-retry-" + now, 3001L, now);
        ProtocolCommandOutbox dead = pendingCommand("dead-" + now, "batch-retry-" + now, 3002L, now);
        mapper.batchInsertPending(List.of(retry, dead));
        Long retryId = insertedId(retry.getCommandId(), now);
        Long deadId = insertedId(dead.getCommandId(), now);
        assertThat(mapper.markLocked(List.of(retryId, deadId), "publisher-a", now + 1)).isEqualTo(2);

        int retried = mapper.markRetry(retryId, now + 30_000, "temporary kafka error", now + 2);
        int deadMarked = mapper.markDead(deadId, "fatal payload error", now + 3);
        int deadMarkedAgain = mapper.markDead(deadId, "second fatal error", now + 4);

        assertThat(retried).isEqualTo(1);
        OutboxState retryState = state(retryId);
        assertThat(retryState.status()).isEqualTo(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(retryState.retryCount()).isEqualTo(1);
        assertThat(retryState.nextRetryAt()).isEqualTo(now + 30_000);
        assertThat(retryState.lockedBy()).isNull();
        assertThat(retryState.lockedAt()).isNull();
        assertThat(retryState.lastError()).isEqualTo("temporary kafka error");
        assertThat(mapper.selectDispatchable(ProtocolCommandOutboxStatus.PENDING.code(), now + 29_999, TEST_SCAN_LIMIT))
                .extracting(ProtocolCommandOutbox::getCommandId)
                .doesNotContain(retry.getCommandId());
        assertThat(mapper.selectDispatchable(ProtocolCommandOutboxStatus.PENDING.code(), now + 30_000, TEST_SCAN_LIMIT))
                .extracting(ProtocolCommandOutbox::getCommandId)
                .contains(retry.getCommandId());

        assertThat(deadMarked).isEqualTo(1);
        assertThat(deadMarkedAgain).isZero();
        OutboxState deadState = state(deadId);
        assertThat(deadState.status()).isEqualTo(ProtocolCommandOutboxStatus.DEAD.code());
        assertThat(deadState.lockedBy()).isNull();
        assertThat(deadState.lockedAt()).isNull();
        assertThat(deadState.lastError()).isEqualTo("fatal payload error");
        assertThat(mapper.selectDispatchable(ProtocolCommandOutboxStatus.PENDING.code(), now + 60_000, TEST_SCAN_LIMIT))
                .extracting(ProtocolCommandOutbox::getCommandId)
                .doesNotContain(dead.getCommandId());
    }

    private Long insertedId(String commandId, long now) {
        return mapper.selectDispatchable(ProtocolCommandOutboxStatus.PENDING.code(), now, TEST_SCAN_LIMIT).stream()
                .filter(row -> row.getCommandId().equals(commandId))
                .map(ProtocolCommandOutbox::getId)
                .findFirst()
                .orElseThrow();
    }

    private ProtocolCommandOutbox findByCommandId(List<ProtocolCommandOutbox> rows, String commandId) {
        return rows.stream()
                .filter(row -> row.getCommandId().equals(commandId))
                .findFirst()
                .orElseThrow();
    }

    private ProtocolCommandOutbox pendingCommand(String commandId, String batchId, long accountId, long now) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setCommandId(commandId);
        row.setBatchId(batchId);
        row.setCommandType("account.online.requested");
        row.setAggregateType("ACCOUNT");
        row.setAggregateId(accountId);
        row.setKafkaTopic("protocol.account.commands.v1");
        row.setKafkaKey("acc_" + accountId);
        row.setProtocolAccountId("acc_" + accountId);
        row.setPayloadJson("{\"accountId\":" + accountId + ",\"proxyId\":" + (accountId + 6000) + "}");
        row.setStatus(ProtocolCommandOutboxStatus.PENDING.code());
        row.setRetryCount(0);
        row.setNextRetryAt(0L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private OutboxState state(Long id) {
        return jdbc.queryForObject(
                "SELECT status, retry_count, next_retry_at, locked_by, locked_at, sent_at, last_error, updated_at "
                        + "FROM protocol_command_outbox WHERE id = ?",
                (rs, rowNum) -> new OutboxState(
                        rs.getInt("status"),
                        rs.getInt("retry_count"),
                        rs.getLong("next_retry_at"),
                        rs.getString("locked_by"),
                        rs.getObject("locked_at", Long.class),
                        rs.getObject("sent_at", Long.class),
                        rs.getString("last_error"),
                        rs.getLong("updated_at")),
                id);
    }

    private record OutboxState(
            int status,
            int retryCount,
            long nextRetryAt,
            String lockedBy,
            Long lockedAt,
            Long sentAt,
            String lastError,
            long updatedAt) {
    }
}
