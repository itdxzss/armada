package com.armada.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.testsupport.DbTestBase;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 验 V005 六表已由 Flyway 迁移到真库(表存在 + 关键列/索引)。 */
class AccountSchemaDbTest extends DbTestBase {

    @Autowired DataSource dataSource;

    @Autowired JdbcTemplate jdbc;

    @Test
    void v005_creates_six_account_tables() throws Exception {
        var expected = java.util.List.of("account", "account_state", "account_group",
                "account_credential", "account_import_batch", "account_import_detail");
        try (var c = dataSource.getConnection()) {
            for (String t : expected) {
                try (var rs = c.getMetaData().getColumns(c.getCatalog(), null, t, "%")) {
                    assertThat(rs.next()).as("表 %s 应存在", t).isTrue();
                }
            }
        }
    }

    @Test
    void account_protocolAccountId_hasLookupIndexForStateEvents() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? "
                        + "ORDER BY seq_in_index",
                String.class,
                "account",
                "idx_tenant_protocol_account");

        assertThat(columns).containsExactly("tenant_id", "protocol_account_id");
    }
}
