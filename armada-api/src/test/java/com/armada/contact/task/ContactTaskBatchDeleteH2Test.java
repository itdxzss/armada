package com.armada.contact.task;

import com.armada.boot.config.MyBatisConfig;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.model.dto.ContactTaskQuery;
import com.armada.contact.task.service.impl.ContactTaskServiceImpl;
import com.armada.contact.task.service.ContactTaskService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实 SQL、租户插件与独立事务验证批量软删除、回滚及调度竞争。 */
class ContactTaskBatchDeleteH2Test {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ContactFriendTaskMapper tasks;
    private ContactTaskService service;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.set(7L);
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V163__contact_friend_task.sql"));
        }
        jdbc.execute("ALTER TABLE contact_friend_task ADD current_round_no BIGINT DEFAULT 0");
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        MyBatisConfig plugins = new MyBatisConfig();
        factory.setPlugins(plugins.mybatisPlusInterceptor(plugins.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/contact/ContactFriendTaskMapper.xml"));
        tasks = new SqlSessionTemplate(factory.getObject()).getMapper(ContactFriendTaskMapper.class);
        ProxyFactory proxy = new ProxyFactory(new ContactTaskServiceImpl(
                tasks, null, null, null, null, TenantContext::get, () -> 1000L));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (ContactTaskService) proxy.getProxy();
        for (int status = 0; status <= 4; status++) {
            jdbc.update("INSERT INTO contact_friend_task(id,tenant_id,name,message_type,content,created_at,updated_at,run_status,is_enabled,task_start_at,next_round_at) VALUES(?,7,'task',1,'hello',1,1,?,1,1,1)",
                    (long) status + 1, status);
        }
        jdbc.update("INSERT INTO contact_friend_task(id,tenant_id,name,message_type,content,created_at,updated_at) VALUES(9,8,'other',1,'hello',1,1)");
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private int delete(List<Long> ids) {
        return service.batchDelete(ids);
    }

    @Test
    void deletesOnlyAllowedStatesDeduplicatesAndHidesFromReadsAndScheduling() {
        jdbc.update("INSERT INTO contact_friend_task_account(tenant_id,task_id,account_id,created_at,updated_at) VALUES(7,1,20,1,1)");
        jdbc.update("INSERT INTO contact_friend_task_recipient(tenant_id,task_id,task_account_id,contact_phone,contact_jid,created_at,updated_at) VALUES(7,1,1,'100','100@lid',1,1)");
        assertThat(delete(List.of(5L, 1L, 3L, 1L))).isEqualTo(3);
        assertThat(tasks.selectById(1L)).isNull();
        ContactTaskQuery query = new ContactTaskQuery(null, null, null, null, 1, 20);
        assertThat(tasks.countPage(query)).isEqualTo(2);
        assertThat(tasks.selectPage(query)).extracting(row -> row.getId()).containsExactly(4L, 2L);
        assertThat(tasks.selectDueScheduledTasks(2000, 20)).isEmpty();
        assertThat(tasks.startDueScheduledTask(1L, 2000)).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM contact_friend_task WHERE id=1", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT next_round_at FROM contact_friend_task WHERE id=1", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contact_friend_task_account", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contact_friend_task_recipient", Integer.class)).isOne();
    }

    @Test
    void runningOrPausedTaskRejectsEntireBatch() {
        for (long blocked : List.of(2L, 4L)) {
            assertThatThrownBy(() -> delete(List.of(1L, blocked)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("先停止");
            assertThat(tasks.selectById(1L)).isNotNull();
        }
    }

    @Test
    void foreignMissingOrDeletedIdsRejectEntireBatchWithoutLeakingData() {
        for (long unavailable : List.of(9L, 99L)) {
            assertThatThrownBy(() -> delete(List.of(1L, unavailable))).isInstanceOf(BusinessException.class);
            assertThat(tasks.selectById(1L)).isNotNull();
        }
        delete(List.of(3L));
        assertThatThrownBy(() -> delete(List.of(1L, 3L))).isInstanceOf(BusinessException.class);
        assertThat(tasks.selectById(1L)).isNotNull();
        TenantContext.clear();
        assertThatThrownBy(() -> delete(List.of(1L))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM contact_friend_task WHERE id=9", Long.class)).isNull();
    }

    @Test
    void rejectsEmptyMalformedAndOversizedBatches() {
        for (List<Long> invalid : Arrays.asList(null, List.<Long>of(), Arrays.asList(1L, null),
                List.of(0L), List.of(-1L), LongStream.rangeClosed(1, 201).boxed().toList())) {
            assertThatThrownBy(() -> delete(invalid)).isInstanceOf(BusinessException.class);
        }
        assertThat(tasks.selectById(1L)).isNotNull();
    }

    @Test
    void rollsBackSoftDeleteWhenTransactionFails() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.batchDelete(List.of(1L, 3L));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.selectById(1L)).isNotNull();
        assertThat(tasks.selectById(3L)).isNotNull();
    }

    @Test
    void deleteWaitsForSchedulerThenRejectsNewRunningState() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var attempted = new CountDownLatch(1);
        var result = new AtomicReference<Future<Integer>>();
        try {
            tx.executeWithoutResult(status -> {
                assertThat(tasks.startDueScheduledTask(1L, 100)).isOne();
                var deleting = executor.submit(() -> {
                    TenantContext.set(7L);
                    try {
                        attempted.countDown();
                        return delete(List.of(1L));
                    } finally { TenantContext.clear(); }
                });
                result.set(deleting);
                assertThatThrownBy(() -> {
                    assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
                    deleting.get(200, TimeUnit.MILLISECONDS);
                }).isInstanceOf(TimeoutException.class);
            });
            assertThatThrownBy(() -> result.get().get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(BusinessException.class)
                    .hasStackTraceContaining("先停止");
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(tasks.selectById(1L).getRunStatus()).isEqualTo(1);
        assertThat(tasks.selectById(1L).getDeletedAt()).isNull();
    }
    @Test
    void schedulerWaitsForDeletionThenCannotStartDeletedTask() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var attempted = new CountDownLatch(1);
        var result = new AtomicReference<Future<Integer>>();
        try {
            tx.executeWithoutResult(status -> {
                assertThat(service.batchDelete(List.of(1L))).isOne();
                result.set(executor.submit(() -> {
                    TenantContext.set(7L);
                    try {
                        attempted.countDown();
                        return tx.execute(inner -> tasks.startDueScheduledTask(1L, 2000));
                    } finally { TenantContext.clear(); }
                }));
                assertThatThrownBy(() -> {
                    assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
                    result.get().get(200, TimeUnit.MILLISECONDS);
                }).isInstanceOf(TimeoutException.class);
            });
            assertThat(result.get().get(5, TimeUnit.SECONDS)).isZero();
            assertThat(tasks.selectById(1L)).isNull();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void permissionMigrationAddsOneButtonPerTenantWithoutGrantingRoles() throws Exception {
        jdbc.execute("CREATE TABLE sys_menu(id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, parent_id BIGINT, menu_name VARCHAR(128), menu_key VARCHAR(128), menu_type VARCHAR(8), route_path VARCHAR(128), component_path VARCHAR(128), perm_key VARCHAR(128), icon VARCHAR(64), sort_no INT, status INT, created_at BIGINT, created_by BIGINT, updated_at BIGINT, updated_by BIGINT, UNIQUE(tenant_id,menu_key))");
        jdbc.update("INSERT INTO sys_menu(id,tenant_id,parent_id,menu_name,menu_key,menu_type,created_at,updated_at) VALUES(1,7,0,'任务','ContactHyperlinkTask','M',10,10),(2,8,0,'任务','ContactHyperlinkTask','M',10,10),(3,7,0,'剧本','ContactScriptTask','M',10,10)");
        var migration = new ClassPathResource("db/migration/V185__contact_task_delete_permission.sql");
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).contains("UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000")
                .doesNotContain("sys_role_menu");
        // H2 不支持 MySQL UNIX_TIMESTAMP；仅替换迁移时钟表达式，真实执行原始插入与租户关联 SQL。
        var h2Migration = new ByteArrayResource(sql.replace(
                "UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000", "1000").getBytes(StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var connection = jdbc.getDataSource().getConnection()) {
                ScriptUtils.executeSqlScript(connection, h2Migration);
            }
        }
        assertThat(jdbc.queryForList("SELECT parent_id FROM sys_menu WHERE perm_key='tenant:contact_task:delete' ORDER BY tenant_id", Long.class))
                .containsExactly(1L, 2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE menu_type='B' AND status=1", Integer.class)).isEqualTo(2);
    }

}
