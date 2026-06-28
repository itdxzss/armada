package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
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
 * 账号生命周期命令服务真库测试。
 *
 * <p>覆盖账号域批量下线到协议命令 outbox 的真实落库链路。测试在事务内执行并回滚;
 * outbox afterCommit dispatch 不会被触发,因此不依赖 Kafka。</p>
 */
class AccountOnlineCommandServiceImplDbTest extends DbTestBase {

    @Autowired
    private AccountOnlineCommandService service;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void offlineBatch_validAccounts_persistsOfflineOutboxRowsWithoutCredentialOrProxyPayload() throws Exception {
        long now = System.currentTimeMillis();
        Account first = insertAccount("86180" + (now % 10_000_000L), now);
        Account second = insertAccount("86181" + (now % 10_000_000L), now);

        AccountBatchOnlineVO result = service.offlineBatch(List.of(first.getId(), second.getId()));

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.submitted()).isEqualTo(2);
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.results()).extracting(AccountBatchOnlineItemVO::accountId)
                .containsExactly(first.getId(), second.getId());
        assertThat(result.results()).extracting(AccountBatchOnlineItemVO::protocolAccountId)
                .containsExactly(first.getProtocolAccountId(), second.getProtocolAccountId());

        List<OutboxRow> rows = selectOfflineOutboxRows(first.getId(), second.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(OutboxRow::commandType)
                .containsOnly("account.offline.requested");
        assertThat(rows).extracting(OutboxRow::aggregateType)
                .containsOnly("ACCOUNT");
        assertThat(rows).extracting(OutboxRow::aggregateId)
                .containsExactly(first.getId(), second.getId());
        assertThat(rows).extracting(OutboxRow::kafkaTopic)
                .containsOnly("protocol.account.commands.v1");
        assertThat(rows).extracting(OutboxRow::kafkaKey)
                .containsExactly(first.getProtocolAccountId(), second.getProtocolAccountId());
        assertThat(rows).extracting(OutboxRow::protocolAccountId)
                .containsExactly(first.getProtocolAccountId(), second.getProtocolAccountId());
        assertThat(rows).extracting(OutboxRow::status)
                .containsOnly(ProtocolCommandOutboxStatus.PENDING.code());

        Map<String, Object> payload = objectMapper.readValue(rows.get(0).payloadJson(), new TypeReference<>() {
        });
        assertThat(((Number) payload.get("accountId")).longValue()).isEqualTo(first.getId());
        assertThat(payload)
                .containsEntry("protocolAccountId", first.getProtocolAccountId())
                .containsEntry("source", "batch_offline");
        assertThat(rows.get(0).payloadJson())
                .doesNotContain("credentialJson")
                .doesNotContain("credentialFormat")
                .doesNotContain("proxyId")
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("proxyHost");
    }

    private Account insertAccount(String wsPhone, long now) {
        Account account = new Account();
        account.setWsPhone(wsPhone);
        account.setAccountType(1);
        account.setOwnership(1);
        account.setPriority(0);
        account.setProtocolAccountId("acc_" + wsPhone);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        accountMapper.insert(account);
        return account;
    }

    private List<OutboxRow> selectOfflineOutboxRows(Long firstAccountId, Long secondAccountId) {
        return jdbc.query(
                "SELECT command_type, aggregate_type, aggregate_id, kafka_topic, kafka_key, "
                        + "protocol_account_id, payload_json, status "
                        + "FROM protocol_command_outbox "
                        + "WHERE tenant_id = ? AND command_type = ? AND aggregate_id IN (?, ?) "
                        + "ORDER BY aggregate_id",
                (rs, rowNum) -> new OutboxRow(
                        rs.getString("command_type"),
                        rs.getString("aggregate_type"),
                        rs.getLong("aggregate_id"),
                        rs.getString("kafka_topic"),
                        rs.getString("kafka_key"),
                        rs.getString("protocol_account_id"),
                        rs.getString("payload_json"),
                        rs.getInt("status")),
                TEST_TENANT_ID,
                "account.offline.requested",
                firstAccountId,
                secondAccountId);
    }

    private record OutboxRow(
            String commandType,
            String aggregateType,
            Long aggregateId,
            String kafkaTopic,
            String kafkaKey,
            String protocolAccountId,
            String payloadJson,
            int status) {
    }
}
