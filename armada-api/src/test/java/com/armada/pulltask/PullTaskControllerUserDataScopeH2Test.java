package com.armada.pulltask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 执行旧拉群配置接口的真实 JDBC SQL，防止详情 JSON 查询绕过 owner。 */
class PullTaskControllerUserDataScopeH2Test {

    private PullTaskController controller;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:pull_task_controller_user_scope;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE pull_task ("
                + "id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_user_id BIGINT NULL,"
                + "task_name VARCHAR(128), group_name VARCHAR(128), mode VARCHAR(32), status VARCHAR(32),"
                + "group_count INT, expected_pull_count INT, config_json VARCHAR(2000),"
                + "operator_name VARCHAR(128), created_at BIGINT, updated_at BIGINT,"
                + "remark VARCHAR(255), deleted_at BIGINT NULL)");
        insert(jdbc, 101L, 7L, 81L, "U1");
        insert(jdbc, 102L, 7L, 82L, "U2");
        insert(jdbc, 103L, 7L, null, "历史");
        insert(jdbc, 104L, 8L, 81L, "其他租户");
        controller = new PullTaskController(jdbc, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    void ordinaryUserReadsOnlyOwnTaskAndConfig() {
        DataScopeContext.open(DataScope.self(81L));

        assertThat(controller.detail(101L, principal(81L)).data())
                .containsEntry("taskName", "U1")
                .extractingByKey("config")
                .isEqualTo(java.util.Map.of("owner", "U1"));
        assertBusinessCode(
                () -> controller.detail(102L, principal(81L)),
                ErrorCode.NOT_FOUND);
        assertBusinessCode(
                () -> controller.detail(103L, principal(81L)),
                ErrorCode.NOT_FOUND);
        assertBusinessCode(
                () -> controller.detail(104L, principal(81L)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void tenantAdminReadsAllTenantOwnersIncludingHistoricalNullOwner() {
        DataScopeContext.open(DataScope.all(9_001L));

        assertThat(controller.detail(101L, adminPrincipal()).data())
                .containsEntry("taskName", "U1");
        assertThat(controller.detail(102L, adminPrincipal()).data())
                .containsEntry("taskName", "U2");
        assertThat(controller.detail(103L, adminPrincipal()).data())
                .containsEntry("taskName", "历史");
        assertBusinessCode(
                () -> controller.detail(104L, adminPrincipal()),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void missingSystemAndMismatchedPrincipalScopesFailClosed() {
        assertBusinessCode(
                () -> controller.detail(101L, principal(81L)),
                ErrorCode.ACCESS_DENIED);

        DataScopeContext.open(DataScope.system("legacy pull task scan"));
        assertBusinessCode(
                () -> controller.detail(101L, principal(81L)),
                ErrorCode.ACCESS_DENIED);

        DataScopeContext.open(DataScope.self(81L));
        assertBusinessCode(
                () -> controller.detail(101L, principal(82L)),
                ErrorCode.ACCESS_DENIED);
    }

    private static void insert(
            JdbcTemplate jdbc,
            long id,
            long tenantId,
            Long ownerUserId,
            String name) {
        jdbc.update("INSERT INTO pull_task (id,tenant_id,owner_user_id,task_name,group_name,mode,status,"
                        + "group_count,expected_pull_count,config_json,operator_name,created_at,updated_at,remark) "
                        + "VALUES (?,?,?,?,?,'OLD_LINK','WAIT_START',1,10,?,?,1000,1000,NULL)",
                id, tenantId, ownerUserId, name, name + "群",
                "{\"owner\":\"" + name + "\"}", name + "操作员");
    }

    private static AuthPrincipal principal(long userId) {
        return new AuthPrincipal(
                userId,
                7L,
                "user-" + userId,
                "用户" + userId,
                "tenant-7",
                "租户7",
                List.of("USER"),
                List.of("tenant:pull_task:view"));
    }

    private static AuthPrincipal adminPrincipal() {
        return new AuthPrincipal(
                9_001L,
                7L,
                "admin",
                "管理员",
                "tenant-7",
                "租户7",
                List.of("TENANT_ADMIN"),
                List.of("tenant:pull_task:view"));
    }

    private static void assertBusinessCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(expected.code()));
    }
}
