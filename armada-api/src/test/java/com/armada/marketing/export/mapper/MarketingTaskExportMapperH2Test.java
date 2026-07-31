package com.armada.marketing.export.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.export.model.entity.MarketingTaskExportJob;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用 H2 MySQL 模式执行导出作业真实 Mapper XML。 */
@SpringJUnitConfig(MarketingTaskExportMapperH2Test.TestMyBatisPlusConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class MarketingTaskExportMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MarketingTaskExportMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS", """
                CREATE TABLE marketing_task_export_job (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    created_by BIGINT NOT NULL,
                    export_mode VARCHAR(32) NOT NULL,
                    task_ids_json VARCHAR(1000) NOT NULL,
                    country_iso2s_json VARCHAR(1000) NOT NULL,
                    request_hash CHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    snapshot_at BIGINT NOT NULL,
                    lease_until BIGINT,
                    claim_token CHAR(36),
                    attempt_count INT NOT NULL,
                    storage_key VARCHAR(255),
                    file_name VARCHAR(255),
                    content_type VARCHAR(128),
                    file_size BIGINT,
                    summary_row_count INT NOT NULL,
                    detail_row_count INT NOT NULL,
                    error_message VARCHAR(500),
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    finished_at BIGINT,
                    expires_at BIGINT,
                    active_request_hash CHAR(64)
                )
                """);
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void jobLifecycleExecutesAndKeepsTenantReadsIsolated() {
        MarketingTaskExportJob job = pendingJob();

        assertThat(mapper.insertJob(job)).isEqualTo(1);
        assertThat(job.getId()).isPositive();
        assertThat(mapper.selectJobByIdForUser(job.getId(), 5L)).isNotNull();
        assertThat(mapper.selectJobByIdForUser(job.getId(), 6L)).isNull();
        assertThat(mapper.selectActiveJob(7L, 5L, "a".repeat(64))).isNotNull();

        TenantContext.set(8L);
        assertThat(mapper.selectJobByIdForUser(job.getId(), 5L)).isNull();

        TenantContext.clear();
        List<MarketingTaskExportJob> processable = mapper.selectProcessableJobs(1_000L, 10);
        assertThat(processable).extracting(MarketingTaskExportJob::getId)
                .containsExactly(job.getId());
        assertThat(mapper.claimJob(7L, job.getId(), 1_000L, 2_000L, "claim-a")).isEqualTo(1);
        assertThat(mapper.renewJobLease(
                7L, job.getId(), "claim-b", 1_050L, 2_050L)).isZero();
        assertThat(mapper.renewJobLease(
                7L, job.getId(), "claim-a", 1_050L, 2_050L)).isEqualTo(1);
        assertThat(mapper.markJobSuccess(completion(job.getId(), "claim-b"))).isZero();
        assertThat(mapper.markJobSuccess(completion(job.getId(), "claim-a"))).isEqualTo(1);

        assertThat(mapper.selectExpiredFiles(1_201L, 20))
                .extracting(MarketingTaskExportJob::getId)
                .containsExactly(job.getId());
        assertThat(mapper.clearExpiredStorage(
                7L, job.getId(), "7/1.xlsx", 1_201L)).isEqualTo(1);

        TenantContext.set(7L);
        MarketingTaskExportJob persisted = mapper.selectJobByIdForUser(job.getId(), 5L);
        assertThat(persisted.getStatus()).isEqualTo("SUCCESS");
        assertThat(persisted.getStorageKey()).isNull();
        assertThat(persisted.getFileSize()).isNull();
    }

    @Test
    void complexFactQueriesBypassAutomaticTenantRewrite() {
        String mapperName = MarketingTaskExportMapper.class.getName();

        assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine(
                mapperName + ".selectCountryEntryRows")).isTrue();
        assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine(
                mapperName + ".selectGroupRows")).isTrue();
        assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine(
                mapperName + ".selectGroupMemberRows")).isTrue();
    }

    private static MarketingTaskExportJob completion(Long id, String claimToken) {
        MarketingTaskExportJob job = new MarketingTaskExportJob();
        job.setTenantId(7L);
        job.setId(id);
        job.setClaimToken(claimToken);
        job.setStorageKey("7/1.xlsx");
        job.setFileName("任务.xlsx");
        job.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        job.setFileSize(3L);
        job.setSummaryRowCount(1);
        job.setDetailRowCount(2);
        job.setFinishedAt(1_100L);
        job.setExpiresAt(1_200L);
        return job;
    }

    private static MarketingTaskExportJob pendingJob() {
        MarketingTaskExportJob job = new MarketingTaskExportJob();
        job.setTenantId(7L);
        job.setCreatedBy(5L);
        job.setExportMode("FULL");
        job.setTaskIdsJson("[9]");
        job.setCountryIso2sJson("[]");
        job.setRequestHash("a".repeat(64));
        job.setStatus("PENDING");
        job.setSnapshotAt(900L);
        job.setAttemptCount(0);
        job.setSummaryRowCount(0);
        job.setDetailRowCount(0);
        job.setCreatedAt(900L);
        job.setUpdatedAt(900L);
        return job;
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestMyBatisPlusConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:marketing_task_export_mapper_test"
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);

            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(mybatisPlusInterceptor);
            factoryBean.setMapperLocations(
                    new ClassPathResource("mapper/marketing/MarketingTaskExportMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        MarketingTaskExportMapper marketingTaskExportMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(MarketingTaskExportMapper.class);
        }
    }
}
