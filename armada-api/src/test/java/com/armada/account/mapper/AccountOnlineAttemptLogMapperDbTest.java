package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.AccountOnlineAttemptLog;
import com.armada.testsupport.DbTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountOnlineAttemptLogMapperDbTest extends DbTestBase {

    @Autowired
    private AccountOnlineAttemptLogMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void schema_hasDiagnosisTimelineColumnsAndIndexes() {
        assertThat(tableExists("account_online_attempt_log")).isTrue();
        assertThat(columnType("account_online_attempt_log", "online_attempt_id")).isEqualTo("varchar");
        assertThat(columnType("account_online_attempt_log", "diagnosis_code")).isEqualTo("varchar");
        assertThat(columnType("account_online_attempt_log", "evidence_json")).isEqualTo("json");
        assertThat(indexColumns("idx_attempt")).containsExactly("tenant_id", "online_attempt_id");
        assertThat(indexColumns("idx_account_time")).containsExactly("tenant_id", "account_id", "occurred_at");
        assertThat(indexColumns("idx_proxy_time")).containsExactly("tenant_id", "proxy_id", "occurred_at");
        assertThat(indexColumns("idx_code_time")).containsExactly("tenant_id", "diagnosis_code", "occurred_at");
        assertThat(indexColumns("idx_tenant_occurred")).containsExactly("tenant_id", "occurred_at", "id");
    }

    @Test
    void insertAndQuery_roundTripsOfflineDiagnosisEvidence() {
        AccountOnlineAttemptLog row = sampleRow("oa_20260702101716_x7k9m2", 9L, 4035L);

        int inserted = mapper.insert(row);

        assertThat(inserted).isEqualTo(1);
        assertThat(row.getId()).isNotNull();

        List<AccountOnlineAttemptLog> byAttempt = mapper.selectByAttemptId("oa_20260702101716_x7k9m2", 20);
        assertThat(byAttempt).singleElement().satisfies(found -> {
            assertThat(found.getTenantId()).isEqualTo(TEST_TENANT_ID);
            assertThat(found.getAccountId()).isEqualTo(9L);
            assertThat(found.getProtocolAccountId()).isEqualTo("acc_252625852450");
            assertThat(found.getOnlineAttemptId()).isEqualTo("oa_20260702101716_x7k9m2");
            assertThat(found.getCommandId()).isEqualTo("cmd_1");
            assertThat(found.getBatchId()).isEqualTo("batch_1");
            assertThat(found.getProxyId()).isEqualTo(4035L);
            assertThat(found.getSource()).isEqualTo("batch_online");
            assertThat(found.getFromState()).isEqualTo("VERIFYING");
            assertThat(found.getToState()).isEqualTo("PROXY_FAILED");
            assertThat(found.getDiagnosisCode()).isEqualTo("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE");
            assertThat(found.getRawCode()).isEqualTo(408);
            assertThat(found.getEvidenceJson()).contains("\"connectionField\"", "\"connecting\"");
        });

        List<AccountOnlineAttemptLog> recent = mapper.selectRecentByAccountId(9L, 10);
        assertThat(recent).extracting(AccountOnlineAttemptLog::getOnlineAttemptId)
                .contains("oa_20260702101716_x7k9m2");
    }

    @Test
    void selectLatestAttemptIdByAccountId_returnsNewestByOccurredAtThenId() {
        AccountOnlineAttemptLog older = sampleRow("oa_older", 11L, 4035L);
        older.setOccurredAt(LocalDateTime.of(2026, 7, 2, 10, 20, 0, 0));
        older.setCreatedAt(LocalDateTime.of(2026, 7, 2, 10, 20, 1, 0));
        AccountOnlineAttemptLog newestByTime = sampleRow("oa_newest_time", 11L, 4035L);
        newestByTime.setOccurredAt(LocalDateTime.of(2026, 7, 2, 10, 22, 0, 0));
        newestByTime.setCreatedAt(LocalDateTime.of(2026, 7, 2, 10, 22, 1, 0));
        AccountOnlineAttemptLog newestById = sampleRow("oa_newest_id", 11L, 4035L);
        newestById.setOccurredAt(LocalDateTime.of(2026, 7, 2, 10, 22, 0, 0));
        newestById.setCreatedAt(LocalDateTime.of(2026, 7, 2, 10, 22, 2, 0));

        mapper.insert(older);
        mapper.insert(newestByTime);
        mapper.insert(newestById);

        assertThat(mapper.selectLatestAttemptIdByAccountId(11L)).isEqualTo("oa_newest_id");
    }

    @Test
    void deleteBefore_deletesRowsBeforeCutoffWithinLimit() {
        AccountOnlineAttemptLog oldest = sampleRow("oa_delete_oldest", 12L, 4035L);
        oldest.setOccurredAt(LocalDateTime.of(2026, 7, 2, 10, 10, 0, 0));
        oldest.setCreatedAt(LocalDateTime.of(2026, 7, 2, 10, 10, 1, 0));
        AccountOnlineAttemptLog beforeCutoff = sampleRow("oa_delete_before_cutoff", 12L, 4035L);
        beforeCutoff.setOccurredAt(LocalDateTime.of(2026, 7, 2, 10, 11, 0, 0));
        beforeCutoff.setCreatedAt(LocalDateTime.of(2026, 7, 2, 10, 11, 1, 0));
        AccountOnlineAttemptLog atCutoff = sampleRow("oa_keep_at_cutoff", 12L, 4035L);
        atCutoff.setOccurredAt(LocalDateTime.of(2026, 7, 2, 10, 12, 0, 0));
        atCutoff.setCreatedAt(LocalDateTime.of(2026, 7, 2, 10, 12, 1, 0));

        mapper.insert(oldest);
        mapper.insert(beforeCutoff);
        mapper.insert(atCutoff);

        int deleted = mapper.deleteBefore(LocalDateTime.of(2026, 7, 2, 10, 12, 0, 0), 1);

        assertThat(deleted).isEqualTo(1);
        assertThat(mapper.selectByAttemptId("oa_delete_oldest", 10)).isEmpty();
        assertThat(mapper.selectByAttemptId("oa_delete_before_cutoff", 10)).hasSize(1);
        assertThat(mapper.selectByAttemptId("oa_keep_at_cutoff", 10)).hasSize(1);
    }

    private static AccountOnlineAttemptLog sampleRow(String attemptId, Long accountId, Long proxyId) {
        AccountOnlineAttemptLog row = new AccountOnlineAttemptLog();
        row.setAccountId(accountId);
        row.setProtocolAccountId("acc_252625852450");
        row.setOnlineAttemptId(attemptId);
        row.setPreviousOnlineAttemptId(null);
        row.setCommandId("cmd_1");
        row.setBatchId("batch_1");
        row.setProxyId(proxyId);
        row.setSource("batch_online");
        row.setFromState("VERIFYING");
        row.setToState("PROXY_FAILED");
        row.setDiagnosisCode("VERIFY_TIMEOUT_NO_CONNECTION_UPDATE");
        row.setDiagnosisClass("PROXY_OR_WA_CONNECTIVITY");
        row.setRawCode(408);
        row.setRawReason("no connection.update open/close before verify timeout");
        row.setRecoverability("RETRYABLE");
        row.setActionTaken("MARK_PROXY_FAILED_RELEASE_SLOT");
        row.setWorkerId("w3");
        row.setEvidenceJson("{\"connectionField\":\"connecting\",\"wsOpen\":false}");
        row.setOccurredAt(LocalDateTime.of(2026, 7, 2, 10, 18, 0, 123_000_000));
        row.setCreatedAt(LocalDateTime.of(2026, 7, 2, 10, 18, 1, 0));
        return row;
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private String columnType(String tableName, String columnName) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = ? AND column_name = ?",
                String.class,
                tableName,
                columnName);
    }

    private List<String> indexColumns(String indexName) {
        return jdbc.query(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'account_online_attempt_log' "
                        + "AND index_name = ? ORDER BY seq_in_index",
                (rs, rowNum) -> rs.getString("column_name"),
                indexName);
    }
}
