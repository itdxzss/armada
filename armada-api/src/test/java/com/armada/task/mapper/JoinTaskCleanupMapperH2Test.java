package com.armada.task.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.WhatsappGroupBusinessDepartureService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.JoinTaskCleanupContext;
import com.armada.task.model.dto.JoinTaskCleanupWork;
import com.armada.task.model.entity.JoinTaskCleanup;
import com.armada.task.model.enums.JoinTaskCleanupStatus;
import com.armada.task.service.JoinTaskCleanupTransactions;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 SQL、租户插件和短事务验证逐项推进、互斥和失败停止。 */
class JoinTaskCleanupMapperH2Test {
    private JdbcTemplate jdbc;
    private JoinTaskMapper tasks;
    private JoinTaskResultMapper results;
    private JoinTaskAdminMapper admins;
    private JoinTaskCleanupMapper cleanups;
    private TransactionTemplate tx;
    private JoinTaskCleanupTransactions service;
    private final WhatsappGroupBusinessDepartureService departures = mock(WhatsappGroupBusinessDepartureService.class);

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:join_cleanup_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        String originalDdl = new ClassPathResource("db/migration/V007__join_task.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(originalDdl.substring(originalDdl.indexOf("CREATE TABLE join_task ("), originalDdl.indexOf(" ENGINE")));
        jdbc.execute("ALTER TABLE join_task ADD owner_user_id BIGINT");
        jdbc.execute("ALTER TABLE join_task ADD is_set_admin_enabled BOOLEAN DEFAULT TRUE");
        jdbc.execute("""
            CREATE TABLE join_task_result(id BIGINT PRIMARY KEY,tenant_id BIGINT,join_task_id BIGINT,
              account_id BIGINT,account VARCHAR(32),link VARCHAR(255),status VARCHAR(16),dispatch_state VARCHAR(16),
              group_jid VARCHAR(64),command_id VARCHAR(64),attempt_no INT DEFAULT 0,next_execute_at BIGINT,
              reason VARCHAR(255),is_admin BOOLEAN DEFAULT TRUE,promoted_at BIGINT,joined_at BIGINT,
              updated_at BIGINT,admin_status TINYINT DEFAULT 3,admin_command_id VARCHAR(64),admin_attempt_no INT DEFAULT 1,
              admin_actor_account_id BIGINT,admin_next_execute_at BIGINT,admin_deadline_at BIGINT,admin_reason VARCHAR(255))
            """);
        createCleanupSchema(jdbc);
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean(); var config = new MyBatisConfig();
        factory.setConfiguration(configuration); factory.setDataSource(ds);
        factory.setPlugins(config.mybatisPlusInterceptor(config.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/task/JoinTaskMapper.xml"),
                new ClassPathResource("mapper/task/JoinTaskResultMapper.xml"),
                new ClassPathResource("mapper/task/JoinTaskAdminMapper.xml"),
                new ClassPathResource("mapper/task/JoinTaskCleanupMapper.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        tasks = sessions.getMapper(JoinTaskMapper.class); results = sessions.getMapper(JoinTaskResultMapper.class);
        admins = sessions.getMapper(JoinTaskAdminMapper.class); cleanups = sessions.getMapper(JoinTaskCleanupMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        service = new JoinTaskCleanupTransactions(cleanups, admins, tasks, results, departures);
        TenantContext.set(7L);
        jdbc.update("INSERT INTO join_task(id,tenant_id,owner_user_id,status,is_clear_admins_and_leave_enabled,total,pending,created_at,updated_at) VALUES(10,7,2,'RUNNING',1,1,1,100,100)");
        jdbc.update("INSERT INTO join_task_result(id,tenant_id,join_task_id,account_id,status,dispatch_state,group_jid,admin_actor_account_id) VALUES(20,7,10,30,'SUCCESS','TERMINAL','123@g.us',40)");
    }
    @AfterEach
    void cleanup() { TenantContext.clear(); }

    /** 使用生产迁移中的建表语句，MySQL 引擎尾句由 H2 测试移除。 */
    static void createCleanupSchema(JdbcTemplate jdbc) throws Exception {
        String migration = new ClassPathResource("db/migration/V203__join_task_clear_admins_and_leave.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("NOT NULL DEFAULT 0"));
        jdbc.execute("ALTER TABLE join_task ADD is_clear_admins_and_leave_enabled BOOLEAN NOT NULL DEFAULT FALSE");
        jdbc.execute(migration.substring(migration.indexOf("CREATE TABLE"), migration.indexOf(" ENGINE=InnoDB")));
        JoinTaskApprovalMapperH2Test.createApprovalSchema(jdbc);
    }

    private void enqueue() {
        tx.executeWithoutResult(s -> service.enqueue(tasks.selectByTenantAndId(10L), admins.find(20L), 100));
    }
    private JoinTaskCleanupWork claim(long now) { return tx.execute(s -> service.claim(20L, now).orElseThrow()); }
    private JoinTaskCleanupContext plan(int size) {
        var members = List.of(new GroupParticipantResult("3@lid", "33333@s.whatsapp.net", "33333", true, false, "admin"),
                new GroupParticipantResult("4@lid", "44444@s.whatsapp.net", "44444", true, false, "admin"),
                new GroupParticipantResult("5@lid", "55555@s.whatsapp.net", "55555", true, false, "admin"));
        return new JoinTaskCleanupContext(members.subList(0, size), 0, "11111", "22222", false);
    }

    @Test
    void absentOriginalCompletesWithoutWritingAnotherLeaveFact() {
        enqueue();
        var listing = claim(100);
        var plan = new JoinTaskCleanupContext(List.of(), 0, "11111", "22222", true);
        tx.executeWithoutResult(s -> service.succeeded(listing, plan, 101));
        assertEquals(JoinTaskCleanupStatus.LEAVE_READY.code(), cleanups.lock(20L).getStatus());
        var leaving = claim(102);
        var restored = JoinTaskCleanupContext.parse(leaving.cleanup().getContextJson());
        assertTrue(restored.originalAlreadyAbsent());
        tx.executeWithoutResult(s -> service.succeeded(leaving, restored, 103));
        assertEquals(JoinTaskCleanupStatus.SUCCESS.code(), cleanups.lock(20L).getStatus());
        assertEquals("DONE", tasks.selectByTenantAndId(10L).getStatus());
        assertEquals(1, tasks.selectByTenantAndId(10L).getSuccess());
        assertNull(cleanups.lock(20L).getActiveGroupJid());
        verifyNoInteractions(departures);
    }

    @Test
    void cleanupSwitchPersistsThroughCreateEditAndTenantScopedRead() {
        var task = tasks.selectByTenantAndId(10L);
        task.setId(null); task.setName("复制配置"); task.setStatus("DRAFT");
        tasks.insert(task);
        assertTrue(tasks.selectByTenantAndId(task.getId()).isClearAdminsAndLeaveEnabled());
        task.setClearAdminsAndLeaveEnabled(false);
        tasks.update(task);
        assertFalse(tasks.selectByTenantAndId(task.getId()).isClearAdminsAndLeaveEnabled());
        TenantContext.set(8L);
        assertNull(tasks.selectByTenantAndId(task.getId()));
    }

    @Test
    void allStagesMustSucceedBeforeTaskCompletesAndReadProjectionMatchesCounters() {
        enqueue();
        tx.executeWithoutResult(s -> { tasks.refreshCounters(10L); assertEquals(0, tasks.markDoneWhenNoPending(10L, 100)); });
        assertEquals(1, tasks.selectByTenantAndId(10L).getPending());
        var listing = claim(100); var plan = plan(1);
        tx.executeWithoutResult(s -> service.succeeded(listing, plan, 101));
        var removing = claim(102);
        tx.executeWithoutResult(s -> service.succeeded(removing, plan.advance(), 103));
        tx.executeWithoutResult(s -> service.succeeded(removing, plan.advance(), 104));
        verify(departures, times(1)).recordConfirmedRemovals(eq(7L),eq("123@g.us"),anyMap(),eq(103L),anyString());
        assertEquals(JoinTaskCleanupStatus.LEAVE_READY.code(), cleanups.lock(20L).getStatus());
        assertEquals("RUNNING", tasks.selectByTenantAndId(10L).getStatus());
        var leaving = claim(105);
        tx.executeWithoutResult(s -> service.succeeded(leaving, plan.advance(), 106));
        assertEquals("DONE", tasks.selectByTenantAndId(10L).getStatus());
        assertEquals(1, tasks.selectByTenantAndId(10L).getSuccess());
        assertEquals(0, tasks.selectByTenantAndId(10L).getPending());
        var projected = results.selectResultsByTask(10L).get(0);
        assertEquals(JoinTaskCleanupStatus.SUCCESS.code(), projected.getCleanupStatus());
        assertEquals("SUCCESS", com.armada.task.model.enums.JoinTaskAdminStatus.stepStatus(projected));
        verify(departures).recordConfirmedLeave(eq(7L),eq("123@g.us"),eq("22222"),eq(106L),anyString());
    }

    @Test
    void secondRemovalFailsAndThirdAndLeaveCannotBeClaimed() {
        enqueue(); var listing = claim(100); var plan = plan(3);
        tx.executeWithoutResult(s -> service.succeeded(listing, plan, 101));
        var first = claim(102);
        tx.executeWithoutResult(s -> service.succeeded(first, plan.advance(), 103));
        var second = claim(104);
        tx.executeWithoutResult(s -> service.failed(second, "GROUP_PERMISSION_DENIED", 105));
        assertTrue(tx.execute(s -> service.claim(20L, 999999)).isEmpty());
        var stopped = cleanups.lock(20L);
        assertEquals(JoinTaskCleanupStatus.FAILED.code(), stopped.getStatus());
        assertTrue(stopped.getReason().contains("4@lid"));
        assertTrue(stopped.getReason().contains("GROUP_PERMISSION_DENIED"));
        assertEquals(1, JoinTaskCleanupContext.parse(stopped.getContextJson()).completed());
        assertEquals(1, tasks.selectByTenantAndId(10L).getFailed());
        assertEquals("SUCCESS", admins.find(20L).getStatus());
        assertEquals(3, admins.find(20L).getAdminStatus());
        verify(departures, never()).recordConfirmedLeave(any(), any(), any(), anyLong(), any());
    }

    @Test
    void timeoutStopsAndLateSuccessCannotTriggerLeave() {
        enqueue(); var listing = claim(100); var plan = plan(1);
        tx.executeWithoutResult(s -> service.succeeded(listing, plan, 101));
        var sending = claim(102);
        assertTrue(tx.execute(s -> service.claim(20L, 120103)).isEmpty());
        tx.executeWithoutResult(s -> service.succeeded(sending, plan.advance(), 120104));
        assertEquals(8, cleanups.lock(20L).getStatus());
        verifyNoInteractions(departures);
    }

    @Test
    void lateSuccessStopsEvenBeforeAnotherWorkerScansDeadline() {
        enqueue(); var listing = claim(100); var plan = plan(1);
        tx.executeWithoutResult(s -> service.succeeded(listing, plan, 101));
        var sending = claim(102);
        tx.executeWithoutResult(s -> service.succeeded(sending, plan.advance(), 120102));
        assertEquals(JoinTaskCleanupStatus.FAILED.code(), cleanups.lock(20L).getStatus());
        assertTrue(cleanups.lock(20L).getReason().contains("超时"));
        assertTrue(tx.execute(s -> service.claim(20L, 120103)).isEmpty());
        assertEquals(1, tasks.selectByTenantAndId(10L).getFailed());
        verifyNoInteractions(departures);
    }

    @Test
    void emptyTargetsStillRequireLeaveAndUnknownLeaveFails() {
        enqueue(); var listing = claim(100); var empty = plan(0);
        tx.executeWithoutResult(s -> service.succeeded(listing, empty, 101));
        var leaving = claim(102);
        assertEquals(6, leaving.cleanup().getStatus());
        tx.executeWithoutResult(s -> service.failed(leaving, "TIMEOUT", 103));
        assertEquals(1, tasks.selectByTenantAndId(10L).getFailed());
        assertTrue(cleanups.lock(20L).getReason().startsWith("LEAVING"));
        verifyNoInteractions(departures);
    }

    @Test
    void missingOriginalStopsWithoutAnyExternalAction() {
        jdbc.update("UPDATE join_task_result SET admin_actor_account_id=NULL WHERE id=20");
        enqueue();
        assertEquals(8, cleanups.lock(20L).getStatus());
        assertEquals(1, tasks.selectByTenantAndId(10L).getFailed());
        assertTrue(cleanups.scan(999999).isEmpty());
    }

    @Test
    void tenantIsolationRollbackAndSameGroupMutualExclusion() {
        enqueue();
        TenantContext.set(8L); assertNull(cleanups.lock(20L)); TenantContext.set(7L);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            service.claim(20L, 100); throw new IllegalStateException("rollback");
        }));
        assertEquals(1, cleanups.lock(20L).getStatus());
        claim(100);
        var other = new JoinTaskCleanup(); other.setResultId(21L); other.setJoinTaskId(10L);
        other.setGroupJid("123@g.us"); other.setStatus(1); other.setContextJson(""); other.setReason("");
        other.setNextExecuteAt(100L); other.setUpdatedAt(100L);
        cleanups.insert(other);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> tx.executeWithoutResult(s -> {
            var c = cleanups.lock(21L); c.setActiveGroupJid("123@g.us"); cleanups.update(c);
        }));
        assertNull(cleanups.lock(21L).getActiveGroupJid());
        // 等待同群互斥的行不能占满扫描窗口，阻塞持有者的后续步骤。
        var due = cleanups.scan(120100);
        assertEquals(1, due.size());
        assertEquals(20L, due.get(0).resultId());
    }

    @Test
    void rowLockMakesSecondWorkerWaitAndThenRejectsDuplicateClaim() throws Exception {
        enqueue();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = CompletableFuture.runAsync(() -> {
            TenantContext.set(7L);
            try { tx.executeWithoutResult(s -> {
                assertTrue(service.claim(20L, 100).isPresent()); locked.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }); } finally { TenantContext.clear(); }
        });
        assertTrue(locked.await(5,TimeUnit.SECONDS));
        var started = new CountDownLatch(1);
        var second = CompletableFuture.supplyAsync(() -> {
            TenantContext.set(7L);
            try { started.countDown(); return tx.execute(s -> service.claim(20L, 100)); }
            finally { TenantContext.clear(); }
        });
        assertTrue(started.await(5,TimeUnit.SECONDS));
        try { assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(100,TimeUnit.MILLISECONDS)); }
        finally { release.countDown(); }
        first.get(5,TimeUnit.SECONDS); assertTrue(second.get(5,TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void deletionAfterListingStopsAndReleasesGroupWithoutSendingAnotherAction() {
        enqueue(); var listing = claim(100);
        tx.executeWithoutResult(s -> service.succeeded(listing, plan(1), 101));
        jdbc.update("UPDATE join_task SET deleted_at=102 WHERE id=10");
        assertEquals(1, cleanups.scan(103).size());
        assertTrue(tx.execute(s -> service.claim(20L, 103)).isEmpty());
        assertEquals(8, cleanups.lock(20L).getStatus());
        assertNull(cleanups.lock(20L).getActiveGroupJid());
        verifyNoInteractions(departures);
    }

    @Test
    void pendingCleanupBlocksAccidentallyActivatedNextJoin() {
        enqueue();
        jdbc.update("INSERT INTO join_task_result(id,tenant_id,join_task_id,account_id,status,dispatch_state,next_execute_at,admin_status) VALUES(21,7,10,30,'PENDING','WAITING',100,1)");
        tx.executeWithoutResult(s -> assertTrue(results.selectDueForUpdate(7L,List.of(21L),101).isEmpty()));
    }
}
