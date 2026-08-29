package com.armada.hyperlink.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskExportJob;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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

/** 公共超链导出作业真实 H2/MyBatis XML、用户隔离和 Worker 类型隔离测试。 */
@SpringJUnitConfig(HyperlinkTaskExportMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class HyperlinkTaskExportMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private HyperlinkTaskExportMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        resetSchema();
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void recipientJobLifecycleKeepsOwnerTenantAndWorkerTypeIsolated() throws SQLException {
        HyperlinkTaskExportJob job = pendingJob();
        assertThat(mapper.insertJob(job)).isEqualTo(1);
        execute("""
                INSERT INTO marketing_task_export_job
                    (tenant_id, created_by, data_scope_mode, export_mode, task_ids_json,
                     country_iso2s_json, request_payload_json, request_hash, status,
                     snapshot_at, attempt_count, summary_row_count, detail_row_count,
                     created_at, updated_at)
                VALUES (7, 5, 'ALL', 'FULL', '[1]', '[]', NULL, 'b', 'PENDING',
                        900, 0, 0, 0, 900, 900)
                """);

        assertThat(mapper.selectJobByIdForUser(job.getId(), 5L)).isNotNull();
        assertThat(mapper.selectJobByIdForUser(job.getId(), 6L)).isNull();
        TenantContext.set(8L);
        assertThat(mapper.selectJobByIdForUser(job.getId(), 5L)).isNull();

        TenantContext.clear();
        assertThat(mapper.selectProcessableRecipientJobs(1_000L, 10))
                .extracting(HyperlinkTaskExportJob::getId)
                .containsExactly(job.getId());
        assertThat(mapper.claimRecipientJob(7L, job.getId(), 1_000L, 2_000L, "claim"))
                .isEqualTo(1);
        assertThat(mapper.renewRecipientJobLease(
                7L, job.getId(), "claim", 1_100L, 2_100L)).isEqualTo(1);

        job.setClaimToken("claim");
        job.setStorageKey("7/1.csv");
        job.setFileName("hyperlink-recipients-9.csv");
        job.setContentType("text/csv;charset=UTF-8");
        job.setFileSize(100L);
        job.setRowCount(3);
        job.setFinishedAt(1_200L);
        job.setExpiresAt(1_300L);
        assertThat(mapper.markRecipientJobSuccess(job)).isEqualTo(1);
        assertThat(mapper.selectExpiredRecipientFiles(1_300L, 10))
                .extracting(HyperlinkTaskExportJob::getId)
                .containsExactly(job.getId());
        assertThat(mapper.clearExpiredRecipientStorage(
                7L, job.getId(), "7/1.csv", 1_300L)).isEqualTo(1);
    }

    private static HyperlinkTaskExportJob pendingJob() {
        HyperlinkTaskExportJob job = new HyperlinkTaskExportJob();
        job.setTenantId(7L);
        job.setCreatedBy(5L);
        job.setDataScopeMode("ALL");
        job.setExportType("RECIPIENTS");
        job.setTaskIdsJson("[9]");
        job.setCountryIso2sJson("[]");
        job.setRequestPayloadJson("{\"taskId\":9}");
        job.setRequestHash("a".repeat(64));
        job.setStatus("PENDING");
        job.setSnapshotAt(900L);
        job.setAttemptCount(0);
        job.setRowCount(0);
        job.setCreatedAt(900L);
        job.setUpdatedAt(900L);
        return job;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE marketing_task_export_job (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    created_by BIGINT NOT NULL,
                    data_scope_mode VARCHAR(8),
                    export_mode VARCHAR(32) NOT NULL,
                    task_ids_json VARCHAR(1000) NOT NULL,
                    country_iso2s_json VARCHAR(1000) NOT NULL,
                    request_payload_json VARCHAR(2000),
                    request_hash VARCHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    snapshot_at BIGINT NOT NULL,
                    lease_until BIGINT,
                    claim_token VARCHAR(36),
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
                    expires_at BIGINT
                )
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:hyperlink_task_export_mapper_test;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/hyperlink/task/HyperlinkTaskExportMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        HyperlinkTaskExportMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(HyperlinkTaskExportMapper.class);
        }
    }
}
