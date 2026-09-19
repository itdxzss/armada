package com.armada.task.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 使用生产租户插件、真实 Mapper 和 Spring 事务验证两阶段结束条件。 */
class JoinTaskAdminMapperH2Test {
    private JdbcTemplate jdbc;
    private JoinTaskMapper tasks;
    private JoinTaskResultMapper results;
    private JoinTaskAdminMapper admins;
    private TransactionTemplate tx;
    private com.armada.group.mapper.AccountGroupMembershipMapper memberships;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:join_admin_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE join_task (id BIGINT PRIMARY KEY, tenant_id BIGINT, owner_user_id BIGINT,
              status VARCHAR(16), is_set_admin_enabled TINYINT DEFAULT 0, total INT DEFAULT 0,
              executed INT DEFAULT 0, success INT DEFAULT 0, failed INT DEFAULT 0, pending INT DEFAULT 0,
              deleted_at BIGINT, updated_at BIGINT)
            """);
        jdbc.execute("""
            CREATE TABLE join_task_result (id BIGINT PRIMARY KEY, tenant_id BIGINT, join_task_id BIGINT,
              account_id BIGINT, status VARCHAR(16), dispatch_state VARCHAR(16), group_jid VARCHAR(64),
              command_id VARCHAR(64), attempt_no INT DEFAULT 0, next_execute_at BIGINT,
              reason VARCHAR(255), is_admin BOOLEAN DEFAULT FALSE, promoted_at BIGINT, joined_at BIGINT,
              updated_at BIGINT, admin_status TINYINT DEFAULT 0, admin_command_id VARCHAR(64),
              admin_attempt_no INT DEFAULT 0, admin_actor_account_id BIGINT, admin_next_execute_at BIGINT,
              admin_deadline_at BIGINT, admin_reason VARCHAR(255) DEFAULT '')
            """);
        JoinTaskCleanupMapperH2Test.createCleanupSchema(jdbc);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        var config = new MyBatisConfig();
        factory.setConfiguration(configuration); factory.setDataSource(ds);
        factory.setPlugins(config.mybatisPlusInterceptor(config.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/task/JoinTaskMapper.xml"),
                new ClassPathResource("mapper/task/JoinTaskResultMapper.xml"),
                new ClassPathResource("mapper/task/JoinTaskAdminMapper.xml"),
                new ClassPathResource("mapper/group/AccountGroupMembershipMapper.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        tasks = sessions.getMapper(JoinTaskMapper.class);
        results = sessions.getMapper(JoinTaskResultMapper.class);
        admins = sessions.getMapper(JoinTaskAdminMapper.class);
        memberships = sessions.getMapper(com.armada.group.mapper.AccountGroupMembershipMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        TenantContext.set(7L);
        jdbc.update("INSERT INTO join_task(id,tenant_id,owner_user_id,status,is_set_admin_enabled,total,pending) VALUES(10,7,2,'RUNNING',1,1,1)");
        jdbc.update("INSERT INTO join_task_result(id,tenant_id,join_task_id,account_id,status,dispatch_state,admin_status,admin_next_execute_at) VALUES(20,7,10,30,'PENDING','SUBMITTED',1,100)");
    }
    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void existingAdminCandidatesRespectOwnerTenantRoleAndOnlineState() {
        jdbc.execute("CREATE TABLE wa_group(id BIGINT, tenant_id BIGINT, group_jid VARCHAR(64))");
        jdbc.execute("CREATE TABLE wa_account_group_binding(tenant_id BIGINT, account_id BIGINT, group_id BIGINT, participant_id BIGINT, last_observed_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_group_participant(id BIGINT, tenant_id BIGINT, group_id BIGINT, presence_status INT, role INT)");
        jdbc.execute("CREATE TABLE account(id BIGINT, tenant_id BIGINT, owner_user_id BIGINT, protocol_id VARCHAR(32), protocol_account_id VARCHAR(64), ws_phone VARCHAR(32), deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE account_state(account_id BIGINT, tenant_id BIGINT, login_state INT, account_state INT, risk_status INT, mute_status INT)");
        jdbc.update("INSERT INTO wa_group VALUES(1,7,'123@g.us'),(2,8,'123@g.us')");
        for (int i = 1; i <= 6; i++) {
            long tenant = i == 6 ? 8 : 7;
            long group = i == 6 ? 2 : 1;
            jdbc.update("INSERT INTO account VALUES(?,?,?,'WEB',?,'12345',NULL)", i, tenant, i == 2 ? 3 : 2, "acc-" + i);
            jdbc.update("INSERT INTO account_state VALUES(?,?,?,2,1,1)", i, tenant, i == 3 ? 0 : 1);
            jdbc.update("INSERT INTO wa_group_participant VALUES(?,?,?,1,?)", i, tenant, group, i == 4 ? 1 : 2);
            jdbc.update("INSERT INTO wa_account_group_binding VALUES(?,?,?,?,100)", tenant, i, group, i);
        }
        var selected = memberships.selectJoinTaskAdminCandidatesByTenant(7L, "123@g.us", 5L, 2L);
        assertEquals(java.util.List.of(1L), selected.stream().map(com.armada.group.model.vo.GroupExecutionAccount::accountId).toList());
        jdbc.update("UPDATE account SET owner_user_id=NULL WHERE id=1");
        assertEquals(1, memberships.selectJoinTaskAdminCandidatesByTenant(7L, "123@g.us", 5L, 2L).size());
    }

    @Test
    void joinedIsNotCompleteUntilAdminSuccess() {
        assertTrue(tasks.selectByTenantAndId(10L).isSetAdminEnabled());
        assertEquals(2L, tasks.selectByTenantAndId(10L).getOwnerUserId());
        tx.executeWithoutResult(s -> {
            results.markTerminalSuccess(20L, "123@g.us", 100L, null, 0);
            tasks.refreshCounters(10L);
            assertEquals(0, tasks.markDoneWhenNoPending(10L, 100L));
        });
        assertEquals(1, tasks.selectByTenantAndId(10L).getPending());
        assertEquals(0, tasks.selectByTenantAndId(10L).getSuccess());
        tx.executeWithoutResult(s -> {
            var row = admins.lock(20L);
            row.setAdminStatus(3); row.setAdmin(true); row.setPromotedAt(200L);
            row.setAdminNextExecuteAt(null); row.setUpdatedAt(200L);
            assertEquals(1, admins.update(row));
            tasks.refreshCounters(10L);
            assertEquals(1, tasks.markDoneWhenNoPending(10L, 200L));
        });
        assertEquals("DONE", tasks.selectByTenantAndId(10L).getStatus());
        assertEquals(1, tasks.selectByTenantAndId(10L).getSuccess());
        assertEquals(0, tasks.selectByTenantAndId(10L).getPending());
        assertTrue(admins.find(20L).isAdmin());
    }

    @Test
    void promotionFailureCountsFailureButKeepsJoinSuccess() {
        results.markTerminalSuccess(20L, "123@g.us", 100L, null, 0);
        tx.executeWithoutResult(s -> {
            var row = admins.lock(20L); row.setAdminStatus(4); row.setAdminReason("管理员权限不足");
            admins.update(row); tasks.refreshCounters(10L); tasks.markDoneWhenNoPending(10L, 200L);
        });
        assertEquals("SUCCESS", admins.find(20L).getStatus());
        assertEquals(1, tasks.selectByTenantAndId(10L).getFailed());
        assertEquals(0, tasks.selectByTenantAndId(10L).getSuccess());
    }

    @Test
    void tenantIsolationAndRollbackPreserveStage() {
        results.markTerminalSuccess(20L, "123@g.us", 100L, null, 0);
        TenantContext.set(8L);
        assertNull(admins.find(20L));
        assertNull(tasks.selectByTenantAndId(10L));
        TenantContext.set(7L);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            var row = admins.lock(20L); row.setAdminStatus(3); admins.update(row);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(1, admins.find(20L).getAdminStatus());
        assertEquals(1, admins.scan(101L, 20).size());
    }

    @Test
    void adminWaitBlocksPrematurelyActivatedNextJoin() {
        results.markTerminalSuccess(20L, "123@g.us", 100L, null, 0);
        jdbc.update("INSERT INTO join_task_result(id,tenant_id,join_task_id,account_id,status,dispatch_state,next_execute_at) VALUES(21,7,10,30,'PENDING','WAITING',100)");
        tx.executeWithoutResult(s -> assertTrue(results.selectDueForUpdate(7L, java.util.List.of(21L), 101L).isEmpty()));
    }
}
