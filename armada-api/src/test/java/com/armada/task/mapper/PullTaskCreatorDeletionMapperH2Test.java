package com.armada.task.mapper;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(PullTaskCreatorDeletionMapperH2Test.Config.class)
@TestExecutionListeners(listeners=DependencyInjectionTestExecutionListener.class, inheritListeners=false)
class PullTaskCreatorDeletionMapperH2Test {
    @Autowired DataSource dataSource;
    @Autowired PullTaskCreatorDeletionMapper mapper;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach void setup() throws Exception {
        TenantContext.set(7L);
        var sql = new ClassPathResource("db/migration/V211__new_group_creator_deletion.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String table = sql.substring(sql.indexOf("CREATE TABLE IF NOT EXISTS pull_task_creator_deletion"));
        table = table.substring(0,table.indexOf(';')+1);
        new JdbcTemplate(dataSource).execute("DROP ALL OBJECTS");
        new JdbcTemplate(dataSource).execute(table);
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void migrationTableAndRealMapperFreezeOperationAndClaimExactlyOnce() {
        var row = row();
        assertThat(mapper.insert(row)).isEqualTo(1);
        assertThat(mapper.claimSubmission(row)).isEqualTo(1);
        assertThat(mapper.claimSubmission(row)).isZero();
        var persisted = mapper.selectByExecutionId(11);
        assertThat(persisted.getStatus()).isEqualTo(1);
        assertThat(persisted.getOperationId()).isEqualTo("original");
        persisted.setCreatorAccountId(999L); persisted.setCreatorIdentityHash("replacement");
        persisted.setStatus(3); persisted.setAttempts(1); persisted.setReasonCode("UNKNOWN");
        assertThat(mapper.updateObservation(persisted)).isEqualTo(1);
        assertThat(mapper.selectByExecutionId(11).getCreatorAccountId()).isEqualTo(99L);
        assertThat(mapper.selectByExecutionId(11).getCreatorIdentityHash()).isEqualTo("hash");
    }
    @Test void wrongTenantCannotQueryOrSubmitAndCompleteCannotRegress() {
        var row = row();mapper.insert(row);
        TenantContext.set(8L);
        assertThat(mapper.selectByExecutionId(11)).isNull();
        assertThat(mapper.claimSubmission(row)).isZero();
        TenantContext.set(7L);mapper.claimSubmission(row);row.setStatus(5);
        assertThat(mapper.updateObservation(row)).isEqualTo(1);
        row.setStatus(3);
        assertThat(mapper.updateObservation(row)).isZero();
        assertThat(mapper.selectByExecutionId(11).getStatus()).isEqualTo(5);
    }
    @Test void independentTransactionsSerializeSubmissionWithoutSecondPostPermit() throws Exception {
        var row = row();mapper.insert(row);
        var tx = new TransactionTemplate(txManager);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var competing = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { TenantContext.set(7L); try {
                return tx.execute(status -> { mapper.selectByExecutionIdForUpdate(11);locked.countDown();
                    try { if (!release.await(5,TimeUnit.SECONDS)) { throw new IllegalStateException("timeout"); } }
                    catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                    return mapper.claimSubmission(row); });
            } finally { TenantContext.clear(); } });
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> { TenantContext.set(7L); try { competing.countDown();
                return tx.execute(status -> mapper.claimSubmission(row));
            } finally { TenantContext.clear(); } });
            assertThat(competing.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse();
            release.countDown();
            assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(5,TimeUnit.SECONDS)).isZero();
        } finally { release.countDown();pool.shutdownNow(); }
    }
    private static PullTaskCreatorDeletion row() {
        var row=new PullTaskCreatorDeletion();row.setTenantId(7L);row.setTaskId(9L);row.setGroupExecutionId(11L);
        row.setCreatorAccountId(99L);row.setCreatorIdentityHash("hash");row.setCreatorPhone("100");
        row.setCreatorProtocolAccountId("100");row.setCreateOperationId("create");row.setOperationId("original");
        row.setStatus(0);row.setAttempts(0);row.setCreatedAt(1000L);row.setUpdatedAt(1000L);
        row.setCreationBefore(12L);row.setSubmittedAt(1000L);row.setDeadlineAt(1000000L);return row;
    }
    @Configuration(proxyBeanMethods=false) @Import(MyBatisConfig.class)
    static class Config {
        @Bean DataSource dataSource() { return PullTaskNormalLinkH2Support.dataSource("creator_deletion_ledger"); }
        @Bean SqlSessionFactory factory(DataSource ds, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(ds,interceptor,"mapper/task/PullTaskCreatorDeletionMapper.xml"); }
        @Bean SqlSessionTemplate template(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean PullTaskCreatorDeletionMapper mapper(SqlSessionTemplate template) { return template.getMapper(PullTaskCreatorDeletionMapper.class); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    }
}
