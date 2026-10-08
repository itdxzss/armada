package com.armada.account.takeover;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.entity.AccountState;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.update.Update;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证存量代理快照 MySQL JOIN SQL 结构及 H2 方言适配后的真实更新语义。 */
class AccountTakeoverProxySnapshotDialectTest {

    private AccountTakeoverH2Support h;

    @BeforeEach
    void setUp() throws Exception {
        h = new AccountTakeoverH2Support();
        h.account(101L, 7, 2, 1, null);
        h.account(102L, 7, 2, 1, null);
        h.account(201L, 7, 2, 1, null);
        h.jdbc.update("UPDATE account SET tenant_id=2 WHERE id=201");
        h.jdbc.update("UPDATE account_state SET tenant_id=2 WHERE account_id=201");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void originalMapperUsesJoinedBatchAndPreservesAllFourSnapshotAssignments() throws Exception {
        var statement = h.sqlSessionFactory.getConfiguration().getMappedStatement(
                "com.armada.account.mapper.AccountStateMapper.updateProxySnapshotsInternal");
        var sql = statement.getBoundSql(Map.of("rows", List.of(snapshot(101L), snapshot(102L))));
        String nativeSql = sql.getSql().replaceAll("\\s+", " ").trim();

        assertThat(CCJSqlParserUtil.parse(nativeSql)).isInstanceOf(Update.class);
        assertThat(nativeSql).contains("UPDATE account_state state_row JOIN (", "UNION ALL",
                "snapshot ON snapshot.account_id = state_row.account_id",
                "state_row.truth_ip = snapshot.truth_ip", "state_row.proxy_country = snapshot.proxy_country",
                "state_row.proxy_source = snapshot.proxy_source", "state_row.updated_at = snapshot.updated_at")
                .doesNotContain("MERGE");
        assertThat(sql.getParameterMappings()).hasSize(10);
    }

    @Test
    void adaptedMapperUpdatesEachBoundSnapshotAndKeepsOtherTenantUntouched() {
        h.transactions.executeWithoutResult(status -> assertThat(h.states.updateProxySnapshots(
                List.of(snapshot(101L), snapshot(102L), snapshot(201L)))).isEqualTo(2));

        assertSnapshot(101L);
        assertSnapshot(102L);
        assertThat(h.jdbc.queryForObject("SELECT truth_ip FROM account_state WHERE account_id=201", String.class))
                .isNull();
        assertThat(h.jdbc.queryForObject("SELECT updated_at FROM account_state WHERE account_id=201", Long.class))
                .isEqualTo(1_000L);
    }

    @Test
    void adaptedSnapshotUpdateParticipatesInTheSurroundingTransaction() {
        h.transactions.executeWithoutResult(status -> {
            assertThat(h.states.updateProxySnapshots(List.of(snapshot(101L)))).isOne();
            assertSnapshot(101L);
            status.setRollbackOnly();
        });

        assertThat(h.jdbc.queryForObject("SELECT truth_ip FROM account_state WHERE account_id=101", String.class))
                .isNull();
        assertThat(h.jdbc.queryForObject("SELECT updated_at FROM account_state WHERE account_id=101", Long.class))
                .isEqualTo(1_000L);
    }

    private void assertSnapshot(long id) {
        Map<String, Object> row = h.jdbc.queryForMap("SELECT truth_ip,proxy_country,proxy_source,updated_at "
                + "FROM account_state WHERE account_id=?", id);
        assertThat(row).containsEntry("truth_ip", "127.0.0." + id)
                .containsEntry("proxy_country", "region-" + id).containsEntry("proxy_source", "source-" + id)
                .containsEntry("updated_at", 2_000L + id);
    }

    private static AccountState snapshot(long id) {
        var row = new AccountState();
        row.setAccountId(id);
        row.setTruthIp("127.0.0." + id);
        row.setProxyCountry("region-" + id);
        row.setProxySource("source-" + id);
        row.setUpdatedAt(2_000L + id);
        return row;
    }
}
