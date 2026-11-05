package com.armada.account.tiercheck;

import java.nio.charset.StandardCharsets;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.assertThat;

class AccountTierCheckMigrationTest {
    @Test void seedIsIdempotentTenantScopedAndUsesApprovedMenuPosition() throws Exception {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:tier_menu;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE sys_menu(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,parent_id BIGINT,menu_name VARCHAR(100),menu_key VARCHAR(100),menu_type VARCHAR(1),route_path VARCHAR(100),component_path VARCHAR(100),perm_key VARCHAR(100),icon VARCHAR(100),sort_no INT,status INT,created_at BIGINT,created_by BIGINT,updated_at BIGINT,updated_by BIGINT,UNIQUE(tenant_id,menu_key))");
        jdbc.update("INSERT INTO sys_menu(tenant_id,menu_key,menu_type,created_at,updated_at) VALUES(7,'AccountManagement','D',100,100),(8,'AccountManagement','D',100,100)");
        try (var c = ds.getConnection()) {
            var resource = new ClassPathResource("db/migration/V226__account_tier_check_menu.sql");
            ScriptUtils.executeSqlScript(c, resource); ScriptUtils.executeSqlScript(c, resource);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE menu_key='AccountTierCheck'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sort_no FROM sys_menu WHERE tenant_id=7 AND menu_key='AccountTierCheck'", Integer.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT perm_key FROM sys_menu WHERE tenant_id=7 AND menu_key='AccountTierCheck'", String.class)).isEqualTo("tenant:account:view");
        assertThat(jdbc.queryForObject("SELECT component_path FROM sys_menu WHERE tenant_id=7 AND menu_key='AccountTierCheck'", String.class)).isEqualTo("account/tier-check/index");
    }
    @Test void mysqlSpecificJsonAndIndexCommentsRemainInProductionDdl() throws Exception {
        String sql = new ClassPathResource("db/migration/V225__account_tier_check.sql").getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).contains("probe_account_ids JSON NOT NULL", "UNIQUE KEY uq_tier_request (tenant_id,created_by,request_id)",
                "UNIQUE KEY uq_tier_account (tenant_id,task_id,account_id)", "idx_tier_executor (tenant_id,executor_account_id,next_execute_at)");
        for (String line : sql.lines().toList()) {
            if (line.strip().startsWith("KEY ") || line.strip().startsWith("UNIQUE KEY ")) assertThat(line).contains("COMMENT '");
        }
    }
}
