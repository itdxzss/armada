package com.armada.task.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.JoinTaskApprovalWork;
import com.armada.task.model.dto.JoinTaskResultReportedEvent;
import com.armada.task.model.enums.JoinTaskApprovalStage;
import com.armada.task.service.JoinTaskApprovalTransactions;
import com.armada.task.service.JoinTaskIntervalPolicy;
import com.armada.task.service.impl.JoinTaskResultServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实 Mapper、租户插件和事务覆盖待审接管、完成、重启与旧回执。 */
class JoinTaskApprovalMapperH2Test {
    private JdbcTemplate jdbc;
    private JoinTaskApprovalMapper approvals;
    private JoinTaskResultMapper results;
    private JoinTaskMapper tasks;
    private JoinTaskResultServiceImpl callback;
    private JoinTaskApprovalTransactions service;
    private TransactionTemplate tx;
    private final AccountGroupMembershipStatusService memberships = mock(AccountGroupMembershipStatusService.class);
    private final MarketingNewGroupImmediateSendService marketing = mock(MarketingNewGroupImmediateSendService.class);

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:join_approval_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        String ddl = new ClassPathResource("db/migration/V007__join_task.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(ddl.substring(ddl.indexOf("CREATE TABLE join_task ("), ddl.indexOf(" ENGINE")));
        jdbc.execute("ALTER TABLE join_task ADD owner_user_id BIGINT");
        jdbc.execute("ALTER TABLE join_task ADD is_set_admin_enabled BOOLEAN DEFAULT TRUE");
        jdbc.execute("""
            CREATE TABLE join_task_result(id BIGINT PRIMARY KEY,tenant_id BIGINT,join_task_id BIGINT,
              account_id BIGINT,account VARCHAR(32),link VARCHAR(255),status VARCHAR(16),dispatch_state VARCHAR(16),
              group_jid VARCHAR(64) DEFAULT '',command_id VARCHAR(64),attempt_no INT DEFAULT 0,next_execute_at BIGINT,
              reason VARCHAR(255) DEFAULT '',is_admin BOOLEAN DEFAULT FALSE,promoted_at BIGINT,joined_at BIGINT,
              updated_at BIGINT,admin_status TINYINT DEFAULT 1,admin_command_id VARCHAR(64),admin_attempt_no INT DEFAULT 0,
              admin_actor_account_id BIGINT,admin_next_execute_at BIGINT,admin_deadline_at BIGINT,admin_reason VARCHAR(255))
            """);
        JoinTaskCleanupMapperH2Test.createCleanupSchema(jdbc);
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean(); var config = new MyBatisConfig();
        factory.setConfiguration(configuration); factory.setDataSource(ds);
        factory.setPlugins(config.mybatisPlusInterceptor(config.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/task/JoinTaskMapper.xml"),
                new ClassPathResource("mapper/task/JoinTaskResultMapper.xml"),
                new ClassPathResource("mapper/task/JoinTaskAdminMapper.xml"),
                new ClassPathResource("mapper/task/JoinTaskApprovalMapper.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        approvals = sessions.getMapper(JoinTaskApprovalMapper.class);
        results = sessions.getMapper(JoinTaskResultMapper.class); tasks = sessions.getMapper(JoinTaskMapper.class);
        callback = new JoinTaskResultServiceImpl(results, tasks, approvals, new JoinTaskIntervalPolicy(), memberships, marketing, () -> 10000L);
        service = new JoinTaskApprovalTransactions(approvals, sessions.getMapper(JoinTaskAdminMapper.class), tasks, callback);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        TenantContext.set(7L);
        jdbc.update("INSERT INTO join_task(id,tenant_id,owner_user_id,status,total,pending,created_at,updated_at) VALUES(10,7,90,'RUNNING',2,2,1,1)");
        jdbc.update("""
            INSERT INTO join_task_result(id,tenant_id,join_task_id,account_id,link,status,dispatch_state,command_id,attempt_no)
            VALUES(20,7,10,30,'https://chat.whatsapp.com/abc','PENDING','SUBMITTED','cmd',1),
                  (21,7,10,30,'https://chat.whatsapp.com/def','PENDING','WAITING',NULL,0)
            """);
    }

    /** 从生产迁移创建子记录，不能用手写的简化替代表结构。 */
    static void createApprovalSchema(JdbcTemplate jdbc) throws Exception {
        String migration = new ClassPathResource("db/migration/V206__join_task_pending_approval.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(migration.substring(migration.indexOf("CREATE TABLE"), migration.indexOf(" ENGINE=InnoDB")));
    }
    @AfterEach
    void cleanup() { TenantContext.clear(); }
    private void pending() { tx.executeWithoutResult(s -> callback.apply(event("PENDING_APPROVAL"))); }
    private JoinTaskResultReportedEvent event(String outcome) {
        return new JoinTaskResultReportedEvent("event",7L,10L,20L,30L,"target","cmd",1,outcome,
                "120@g.us","", "",false,10000L,"");
    }
    private JoinTaskApprovalWork claim(long now) { return tx.execute(s -> service.claim(20L,now).orElseThrow()); }
    private void advance(JoinTaskApprovalWork work, JoinTaskApprovalStage next, long now) {
        tx.executeWithoutResult(s -> service.succeeded(work,next,now));
    }
    private String value(String column) { return jdbc.queryForObject("SELECT " + column + " FROM join_task_result WHERE id=20",String.class); }

    @Test
    void pendingIsProcessingAndDuplicateDoesNotCreateAnotherRecordOrAdvanceLane() {
        pending(); pending();
        assertEquals("PENDING",value("status")); assertEquals("APPROVAL",value("dispatch_state"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM join_task_approval",Integer.class));
        assertNull(jdbc.queryForObject("SELECT next_execute_at FROM join_task_result WHERE id=21",Long.class));
        tx.executeWithoutResult(s -> tasks.refreshCounters(10L));
        assertEquals(0,tasks.selectByTenantAndId(10L).getFailed());
        assertEquals(2,tasks.selectByTenantAndId(10L).getPending());
        assertEquals(1,results.selectResultsByTask(10L).get(0).getApprovalStage());
        verifyNoInteractions(memberships,marketing);
    }
    @Test
    void ordinaryJoinNeverCreatesApprovalWork() {
        tx.executeWithoutResult(s -> callback.apply(event("JOINED")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM join_task_approval",Integer.class));
        assertEquals("SUCCESS",value("status"));
    }
    @Test
    void onlyConfirmedMembershipTriggersExistingSuccessAndPromotionExactlyOnce() {
        pending(); var work=claim(10000); work.approval().setTargetPhone("111");
        advance(work,JoinTaskApprovalStage.CLOSE,10001);
        var closing=claim(10002); advance(closing,JoinTaskApprovalStage.CHECK,10003);
        assertEquals("PENDING",value("status")); assertNull(value("admin_next_execute_at"));
        var checking=claim(10004); advance(checking,JoinTaskApprovalStage.SUCCESS,10005);
        advance(checking,JoinTaskApprovalStage.SUCCESS,10006);
        assertEquals("SUCCESS",value("status")); assertEquals("10000",value("admin_next_execute_at"));
        assertNull(jdbc.queryForObject("SELECT next_execute_at FROM join_task_result WHERE id=21",Long.class));
        verify(memberships,times(1)).applyMembershipChanged(any());
        verify(marketing,times(1)).enqueueDelayedNewGroups(any(),any(),anyLong());
    }
    @Test
    void explicitFailureTerminatesAndAdvancesLaneWithExactReason() {
        pending(); var work=claim(10000);
        tx.executeWithoutResult(s -> service.failed(work,"关闭群组审核失败：无管理员权限",false,10001));
        assertEquals("FAILED",value("status")); assertEquals("JOIN_APPROVAL_FAILED",value("reason"));
        assertEquals("关闭群组审核失败：无管理员权限",results.selectResultsByTask(10L).get(0).getApprovalReason());
        assertNotNull(jdbc.queryForObject("SELECT next_execute_at FROM join_task_result WHERE id=21",Long.class));
        verifyNoInteractions(memberships,marketing);
    }
    @Test
    void expiredCloseIsNotRepeatedAndCannotAcceptLateSuccess() {
        pending(); advance(claim(10000),JoinTaskApprovalStage.CLOSE,10001);
        var work=claim(10002);
        assertTrue(tx.execute(s -> service.claim(20L,130002)).isEmpty());
        advance(work,JoinTaskApprovalStage.CHECK,130003);
        assertEquals("FAILED",value("status"));
        assertTrue(results.selectResultsByTask(10L).get(0).getApprovalReason().contains("结果未确认"));
    }
    @Test
    void interruptedApprovalAndRejoinRecoverToReadOnlyVerification() {
        pending(); advance(claim(10000),JoinTaskApprovalStage.APPROVE,10001);
        var old=claim(10002); var recovered=claim(130002);
        assertEquals(JoinTaskApprovalStage.VERIFY.code(),recovered.approval().getStage());
        advance(old,JoinTaskApprovalStage.SUCCESS,130003);
        assertEquals("PENDING",value("status"));
        tx.executeWithoutResult(s -> service.failed(recovered,"timeout",true,130003));
        assertTrue(tx.execute(s -> service.claim(20L,310000)).isEmpty());
        assertEquals("FAILED",value("status"));
    }
    @Test
    void stoppedTaskDoesNotContinueAndWrongTenantCannotClaim() {
        pending(); TenantContext.set(8L);
        assertTrue(tx.execute(s -> service.claim(20L,10000)).isEmpty());
        TenantContext.set(7L); var work=claim(10000);
        jdbc.update("UPDATE join_task SET status='STOPPED' WHERE id=10");
        advance(work,JoinTaskApprovalStage.CLOSE,10001);
        assertEquals("FAILED",value("status")); verifyNoInteractions(memberships,marketing);
    }
    @Test
    void failedSuccessTransactionRollsBackBothStages() {
        pending(); var work=claim(10000);
        doThrow(new IllegalStateException("membership unavailable")).when(memberships).applyMembershipChanged(any());
        assertThrows(IllegalStateException.class, () -> advance(work,JoinTaskApprovalStage.SUCCESS,10001));
        assertEquals("PENDING",value("status")); assertEquals("APPROVAL",value("dispatch_state"));
        assertEquals(1,tx.execute(s -> approvals.lock(20L)).getStage());
    }

    @Test
    void competingSchedulersWaitForTransactionAndOnlyOneClaimsTheStep() throws Exception {
        pending();
        var claimed = new CountDownLatch(1); var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> {
            TenantContext.set(7L);
            try {
                return tx.execute(s -> {
                    var work = service.claim(20L,10000);
                    claimed.countDown();
                    try { assertTrue(release.await(3,TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                    return work;
                });
            } finally { TenantContext.clear(); }
        });
        assertTrue(claimed.await(3,TimeUnit.SECONDS));
        var second = CompletableFuture.supplyAsync(() -> {
            TenantContext.set(7L); secondStarted.countDown();
            try { return tx.execute(s -> service.claim(20L,10000)); }
            finally { TenantContext.clear(); }
        });
        try {
            assertTrue(secondStarted.await(3,TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse(second.isDone(),"竞争调度器应等待首个短事务释放锁");
        } finally { release.countDown(); }
        assertTrue(first.get(3,TimeUnit.SECONDS).isPresent());
        assertTrue(second.get(3,TimeUnit.SECONDS).isEmpty());
    }
}
