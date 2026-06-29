package com.armada.account.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.account.service.AccountImportService;
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
 * 账号导入自动上线派发真库测试。
 *
 * <p>验证 {@code account_import_detail.online_phase=QUEUED} 成功时会被批量写入协议 outbox
 * 并推进到 DISPATCHED;派发失败时保持 QUEUED 等待后续重试。</p>
 */
class AccountImportOnlineDispatcherDbTest extends DbTestBase {

    @Autowired
    private AccountImportService importService;

    @Autowired
    private AccountImportOnlineDispatcher dispatcher;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private IpProxyMapper ipProxyMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void dispatchOnce_queuedImportDetailsPersistOnlineOutboxAndMarkDispatched() throws Exception {
        long now = System.currentTimeMillis();
        insertIdleProxy(now, "印度");
        insertIdleProxy(now + 1, "印度");

        String firstPhone = "86187" + (now % 10_000_000L);
        String secondPhone = "86188" + (now % 10_000_000L);
        AccountImportBatchVO batch = importTwoAccounts(firstPhone, secondPhone, "印度");
        Account first = accountMapper.selectActiveByWsPhone(firstPhone);
        Account second = accountMapper.selectActiveByWsPhone(secondPhone);

        int dispatched = dispatcher.dispatchOnce();

        assertThat(dispatched).isEqualTo(2);
        List<ImportDetailRow> details = selectImportDetails(batch.id());
        assertThat(details).hasSize(2);
        assertThat(details).extracting(ImportDetailRow::onlinePhase)
                .containsOnly(AccountImportOnlinePhase.DISPATCHED);
        assertThat(details).extracting(ImportDetailRow::dispatchAttempts)
                .containsOnly(1);
        assertThat(details).allSatisfy(row -> assertThat(row.onlineDispatchedAt()).isNotNull());

        List<OutboxRow> outboxRows = selectOnlineOutboxRows(first.getId(), second.getId());
        assertThat(outboxRows).hasSize(2);
        assertThat(outboxRows).extracting(OutboxRow::status)
                .containsOnly(ProtocolCommandOutboxStatus.PENDING.code());
        assertThat(outboxRows).extracting(OutboxRow::aggregateId)
                .containsExactly(first.getId(), second.getId());

        Map<String, Object> payload = objectMapper.readValue(outboxRows.get(0).payloadJson(), new TypeReference<>() {
        });
        assertThat(payload)
                .containsEntry("accountId", first.getId().intValue())
                .containsEntry("protocolAccountId", first.getProtocolAccountId())
                .containsEntry("source", "batch_online");
        assertThat(outboxRows.get(0).payloadJson())
                .doesNotContain("password")
                .doesNotContain("username")
                .doesNotContain("proxyHost");
    }

    @Test
    void dispatchOnce_proxyNotEnoughKeepsDetailsQueuedAndDoesNotWriteOutbox() {
        long now = System.currentTimeMillis();
        disableIdleProxies(now);
        insertIdleProxy(now, "import-online-shortage-" + now);

        String firstPhone = "86189" + (now % 10_000_000L);
        String secondPhone = "86190" + (now % 10_000_000L);
        AccountImportBatchVO batch = importTwoAccounts(firstPhone, secondPhone, "import-online-shortage-" + now);
        Account first = accountMapper.selectActiveByWsPhone(firstPhone);
        Account second = accountMapper.selectActiveByWsPhone(secondPhone);

        int dispatched = dispatcher.dispatchOnce();

        assertThat(dispatched).isZero();
        List<ImportDetailRow> details = selectImportDetails(batch.id());
        assertThat(details).hasSize(2);
        assertThat(details).extracting(ImportDetailRow::onlinePhase)
                .containsOnly(AccountImportOnlinePhase.QUEUED);
        assertThat(details).extracting(ImportDetailRow::dispatchAttempts)
                .containsOnly(0);
        assertThat(details).extracting(ImportDetailRow::onlineDispatchedAt)
                .containsOnlyNulls();
        assertThat(selectOnlineOutboxRows(first.getId(), second.getId())).isEmpty();
    }

    private AccountImportBatchVO importTwoAccounts(String firstPhone, String secondPhone, String ipRegion) {
        String json = "["
                + "{\"wid\":\"" + firstPhone
                + "\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}},"
                + "{\"wid\":\"" + secondPhone
                + "\",\"registrationId\":2,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}"
                + "]";
        var meta = new AccountImportDTO(null, 2, 1, 2, ipRegion, "dispatch-test", null);
        AccountImportBatchVO batch = importService.importAccounts(meta, null, json);
        assertThat(batch.importedRows()).isEqualTo(2);
        return batch;
    }

    private void disableIdleProxies(long updatedAt) {
        jdbc.update(
                """
                UPDATE ip_proxy
                SET status = ?, updated_at = ?
                WHERE tenant_id = ? AND status = ? AND deleted_at IS NULL
                """,
                IpProxyStatus.IN_USE.code(),
                updatedAt,
                TEST_TENANT_ID,
                IpProxyStatus.IDLE.code());
    }

    private void insertIdleProxy(long suffix, String region) {
        IpProxy proxy = new IpProxy();
        proxy.setHost("import-online-proxy-" + suffix + ".internal");
        proxy.setPort(1080);
        proxy.setProtocol(ProxyProtocol.SOCKS5.code());
        proxy.setUsername("dispatchUser" + suffix);
        proxy.setPassword("dispatchPass_session-Abc123" + suffix);
        proxy.setRegion(region);
        proxy.setStatus(IpProxyStatus.IDLE.code());
        proxy.setSource("dbtest");
        proxy.setOwnership(ProxyOwnership.OWNED.code());
        proxy.setCreatedAt(suffix);
        proxy.setUpdatedAt(suffix);
        ipProxyMapper.insert(proxy);
    }

    private List<ImportDetailRow> selectImportDetails(Long batchId) {
        return jdbc.query(
                """
                SELECT online_phase, online_dispatched_at, dispatch_attempts
                FROM account_import_detail
                WHERE tenant_id = ? AND batch_id = ?
                ORDER BY line_no
                """,
                (rs, rowNum) -> new ImportDetailRow(
                        rs.getInt("online_phase"),
                        rs.getObject("online_dispatched_at", Long.class),
                        rs.getInt("dispatch_attempts")),
                TEST_TENANT_ID,
                batchId);
    }

    private List<OutboxRow> selectOnlineOutboxRows(Long firstAccountId, Long secondAccountId) {
        return jdbc.query(
                """
                SELECT aggregate_id, status, payload_json
                FROM protocol_command_outbox
                WHERE tenant_id = ? AND command_type = ? AND aggregate_id IN (?, ?)
                ORDER BY aggregate_id
                """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getLong("aggregate_id"),
                        rs.getInt("status"),
                        rs.getString("payload_json")),
                TEST_TENANT_ID,
                "account.online.requested",
                firstAccountId,
                secondAccountId);
    }

    private record ImportDetailRow(Integer onlinePhase, Long onlineDispatchedAt, Integer dispatchAttempts) {
    }

    private record OutboxRow(Long aggregateId, Integer status, String payloadJson) {
    }
}
