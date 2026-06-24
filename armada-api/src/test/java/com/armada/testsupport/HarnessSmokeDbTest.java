package com.armada.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 冒烟测试:验证真库连通 + Flyway 已对 armada schema 建表(marketing_template 来自 V001)。
 * 跑通即证明 DbTest 骨架(RDS 连接 / Flyway 迁移 / Spring 上下文)可用。
 */
class HarnessSmokeDbTest extends DbTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayMigratedArmadaSchema() throws Exception {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = 'armada' AND table_name = 'marketing_template'")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }
}
