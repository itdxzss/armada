package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.entity.IpProxy;
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
    private AccountCredentialMapper credentialMapper;

    @Autowired
    private AccountStateMapper stateMapper;

    @Autowired
    private IpProxyMapper ipProxyMapper;

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
                .containsOnly("protocol.master.commands.v1");
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

    @Test
    void onlineBatch_validAccounts_snapshotsAllocatedProxyDisplayFields() {
        long now = System.currentTimeMillis();
        Account account = insertAccount("86182" + (now % 10_000_000L), now);
        insertDefaultState(account.getId(), now);
        insertCredential(account, now);
        insertIdleProxy(now);

        AccountBatchOnlineVO result = service.onlineBatch(List.of(account.getId()));

        assertThat(result.accepted()).isEqualTo(1);
        AccountState state = stateMapper.selectByAccountId(account.getId());
        assertThat(state.getProxyCountry()).isEqualTo("印度");
        assertThat(state.getProxySource()).isEqualTo("iproyal");
        assertThat(state.getTruthIp()).isEqualTo("geo.iproyal.com");
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

    private void insertDefaultState(Long accountId, long now) {
        AccountState state = new AccountState();
        state.setAccountId(accountId);
        state.setProxyFailureCount(0);
        state.setPullIntoGroupCount(0);
        state.setCreatedAt(now);
        state.setUpdatedAt(now);
        stateMapper.insert(state);
    }

    private void insertCredential(Account account, long now) {
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(account.getId());
        credential.setWsPhone(account.getWsPhone());
        credential.setCredFormat(2);
        credential.setCredsJson("{\"creds\":{},\"keys\":{}}");
        credential.setCreatedAt(now);
        credential.setUpdatedAt(now);
        credentialMapper.insert(credential);
    }

    private void insertIdleProxy(long now) {
        IpProxy proxy = new IpProxy();
        proxy.setHost("geo.iproyal.com");
        proxy.setPort(12321);
        proxy.setProtocol(ProxyProtocol.SOCKS5.code());
        proxy.setUsername("proxy-user");
        proxy.setPassword("proxy-pass");
        proxy.setRegion("印度");
        proxy.setStatus(IpProxyStatus.IDLE.code());
        proxy.setSource("iproyal");
        proxy.setOwnership(ProxyOwnership.OWNED.code());
        proxy.setCreatedAt(now);
        proxy.setUpdatedAt(now);
        ipProxyMapper.insert(proxy);
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
