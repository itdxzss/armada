package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountStatMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatQuery;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** MySQL 8.4 实库覆盖账号统计累计投影和时间范围事实查询。 */
@EnabledIfSystemProperty(named = "armada.hyperlink.mysql-it", matches = "true")
@Testcontainers
class HyperlinkAccountStatQueryMySqlTest {

    private static final long TENANT_ID = 7L;
    private static final long TASK_ID = 11L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_hyperlink_account_stats")
            .withUsername("armada")
            .withPassword("armada");

    private static JdbcTemplate jdbc;
    private static HyperlinkAccountStatQueryService service;

    @BeforeAll
    static void configureDatabaseAndMappers() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createSchema();

        SqlSessionTemplate template = buildSqlSessionTemplate(dataSource);
        HyperlinkTaskMapper tasks = mock(HyperlinkTaskMapper.class);
        when(tasks.selectById(TASK_ID)).thenReturn(new HyperlinkTask());
        service = new HyperlinkAccountStatQueryService(
                tasks,
                template.getMapper(HyperlinkTaskAccountStatMapper.class),
                template.getMapper(HyperlinkTaskRecipientMapper.class),
                new HyperlinkAccountStatCriteriaFactory(),
                Clock.fixed(Instant.ofEpochMilli(864_000_000L), ZoneOffset.UTC));
    }

    @BeforeEach
    void insertFixtures() {
        jdbc.update("DELETE FROM hyperlink_task_recipient");
        jdbc.update("DELETE FROM hyperlink_task_account_usage");
        jdbc.update("DELETE FROM hyperlink_task_account_stat");
        jdbc.update("""
                INSERT INTO hyperlink_task_account_stat
                  (id, tenant_id, hyperlink_task_id, account_id, send_total, success_num,
                   delivered_num, failed_num, last_send_at, updated_at)
                VALUES (1, 7, 11, 101, 4, 3, 2, 1, 1300, 500)
                """);
        jdbc.update("""
                INSERT INTO hyperlink_task_account_usage
                  (id, tenant_id, hyperlink_task_id, account_id, account_phone_snapshot,
                   sender_country_iso2_snapshot, account_type_snapshot,
                   account_created_at_snapshot)
                VALUES (1, 7, 11, 101, '551100000101', 'BR', 1, 0)
                """);
        jdbc.update("""
                INSERT INTO hyperlink_task_recipient
                  (id, tenant_id, hyperlink_task_id, account_id,
                   sender_country_iso2_snapshot, send_status, submitted_at,
                   created_at, updated_at)
                VALUES
                  (1, 7, 11, 101, 'BR', 3, 1000, 100, 100),
                  (2, 7, 11, 101, 'BR', 4, 1100, 100, 100),
                  (3, 7, 11, 101, 'BR', 5, 1200, 100, 100),
                  (4, 7, 11, 101, 'BR', 6, 1300, 100, 100)
                """);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void cumulativeProjectionExecutesOnMySql84() {
        var result = service.list(TASK_ID, new HyperlinkAccountStatQuery());

        assertThat(result.list()).singleElement().satisfies(row -> {
            assertThat(row.accountId()).isEqualTo(101L);
            assertThat(row.senderCountryIso2()).isEqualTo("BR");
            assertThat(row.successNum()).isEqualTo(3);
        });
    }

    @Test
    void timeScopedFactsExecuteOnMySql84() {
        HyperlinkAccountStatQuery query = new HyperlinkAccountStatQuery();
        query.setStartAt(1_000L);
        query.setEndAt(2_000L);

        var result = service.list(TASK_ID, query);

        assertThat(result.list()).singleElement().satisfies(row -> {
            assertThat(row.accountId()).isEqualTo(101L);
            assertThat(row.successNum()).isEqualTo(3);
            assertThat(row.deliveredNum()).isEqualTo(2);
            assertThat(row.failedNum()).isEqualTo(1);
        });
    }

    private static SqlSessionTemplate buildSqlSessionTemplate(DataSource dataSource)
            throws Exception {
        MyBatisConfig myBatisConfig = new MyBatisConfig();
        MybatisPlusInterceptor interceptor = myBatisConfig.mybatisPlusInterceptor(
                myBatisConfig.tenantLineHandler());
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(interceptor);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskRecipientMapper.xml"),
                new ClassPathResource("mapper/hyperlink/task/HyperlinkTaskAccountStatMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("无法创建账号维度统计 MySQL 测试 SqlSessionFactory");
        }
        return new SqlSessionTemplate(factory);
    }

    private static void createSchema() {
        jdbc.execute("""
                CREATE TABLE hyperlink_task_account_stat (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL,
                  account_id BIGINT NULL,
                  account_bucket_key BIGINT GENERATED ALWAYS AS (COALESCE(account_id, 0)) STORED,
                  send_total BIGINT NOT NULL DEFAULT 0,
                  success_num BIGINT NOT NULL DEFAULT 0,
                  delivered_num BIGINT NOT NULL DEFAULT 0,
                  failed_num BIGINT NOT NULL DEFAULT 0,
                  last_send_at BIGINT NULL,
                  updated_at BIGINT NOT NULL,
                  UNIQUE KEY uk_account_stat
                    (tenant_id, hyperlink_task_id, account_bucket_key)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE hyperlink_task_account_usage (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  account_phone_snapshot VARCHAR(32) NULL,
                  sender_country_iso2_snapshot CHAR(2) NULL,
                  account_type_snapshot TINYINT NULL,
                  account_created_at_snapshot BIGINT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE hyperlink_task_recipient (
                  id BIGINT PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  hyperlink_task_id BIGINT NOT NULL,
                  account_id BIGINT NULL,
                  sender_country_iso2_snapshot CHAR(2) NULL,
                  send_status TINYINT NOT NULL,
                  submitted_at BIGINT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL
                ) ENGINE=InnoDB
                """);
    }
}
