package com.armada.account;

import static org.assertj.core.api.Assertions.assertThat;
import com.armada.testsupport.DbTestBase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 验 V005 六表已由 Flyway 迁移到真库(表存在 + 关键列/索引)。 */
class AccountSchemaDbTest extends DbTestBase {

    @Autowired DataSource dataSource;

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
}
