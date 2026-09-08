package com.armada.feed.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.feed.task.model.dto.FeedTaskQuery;
import com.armada.feed.task.model.entity.FeedTask;
import com.armada.feed.task.model.entity.FeedTaskAccount;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;

/** 动态发布任务真实 Mapper XML、租户隔离与执行状态的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(FeedTaskMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class FeedTaskMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private FeedTaskMapper taskMapper;

    @Autowired
    private FeedTaskAccountMapper accountMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void taskQueriesAndUpdatesStayInsideCurrentTenant() throws SQLException {
        FeedTask current = task("当前租户动态", 100L);
        taskMapper.insert(current);
        execute("""
                INSERT INTO feed_task (
                    tenant_id, name, account_filter, title, content, promotion_link,
                    text_color, background_color, concurrency, retry_max, start_mode,
                    task_delay_minutes, task_mode, status, task_status, current_round_no,
                    total_account_num, success_account_num, failed_account_num,
                    created_at, updated_at)
                VALUES (8, '其他租户动态', '{}', '其他标题', '其他内容', 'https://other.example',
                    '#FFFFFF', '#075E54', 3, 2, 'now', 0, 'instant', 1, 0, 0,
                    0, 0, 0, 200, 200)
                """);

        FeedTaskQuery query = new FeedTaskQuery();
        query.setName("动态");
        query.setTaskStatus(0);
        query.setPage(1);
        query.setPageSize(20);

        assertThat(taskMapper.countPage(query)).isEqualTo(1);
        assertThat(taskMapper.selectPage(query))
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.getId()).isEqualTo(current.getId());
                    assertThat(found.getTenantId()).isEqualTo(7L);
                });
        assertThat(taskMapper.updateRunStatus(current.getId(), 0, 1, 500L, 300L)).isEqualTo(1);
        assertThat(taskMapper.updateRunStatus(current.getId(), 0, 3, null, 301L)).isZero();
        assertThat(taskMapper.selectById(current.getId()).getTaskStatus()).isEqualTo(1);

        TenantContext.set(8L);
        assertThat(taskMapper.selectById(current.getId())).isNull();
        assertThat(taskMapper.countPage(query)).isEqualTo(1);
    }

    @Test
    void accountRowsAreIdempotentAndFollowExpectedSendTransitions() throws SQLException {
        FeedTask task = task("发送状态", 100L);
        taskMapper.insert(task);
        FeedTaskAccount account = account(task.getId(), 501L, "919000000001", 100L);

        assertThat(accountMapper.insert(account)).isEqualTo(1);
        assertThat(accountMapper.insert(account(task.getId(), 501L, "919000000001", 101L))).isZero();
        assertThat(accountMapper.countOpen(task.getId())).isEqualTo(1);
        assertThat(accountMapper.selectDispatchable(task.getId(), 10))
                .extracting(FeedTaskAccount::getAccountId)
                .containsExactly(501L);

        assertThat(accountMapper.markSending(account.getId(), "pending", "cmd-1", 1L, 200L))
                .isEqualTo(1);
        assertThat(accountMapper.markSending(account.getId(), "pending", "cmd-2", 1L, 201L))
                .isZero();
        assertThat(accountMapper.markRetrying(account.getId(), "TEMP", "temporary", 300L))
                .isEqualTo(1);
        assertThat(accountMapper.markSending(account.getId(), "retrying", "cmd-2", 2L, 400L))
                .isEqualTo(1);
        assertThat(accountMapper.markSuccess(account.getId(), "wamid-1", 500L)).isEqualTo(1);
        assertThat(accountMapper.countOpen(task.getId())).isZero();

        FeedTaskAccount found = accountMapper.selectById(account.getId());
        assertThat(found.getSendStatus()).isEqualTo("success");
        assertThat(found.getRetryNum()).isEqualTo(2);
        assertThat(found.getCommandId()).isEqualTo("cmd-2");
        assertThat(found.getProtocolMessageId()).isEqualTo("wamid-1");
        assertThat(found.getFailCode()).isNull();

        execute("""
                INSERT INTO feed_task_account (
                    tenant_id, task_id, account_id, account_phone_snapshot, send_status,
                    retry_num, retry_max, created_at, updated_at)
                VALUES (8, %d, 502, '919000000002', 'pending', 0, 2, 100, 100)
                """.formatted(task.getId()));
        assertThat(accountMapper.countPage(task.getId(), null)).isEqualTo(1);
    }

    private static FeedTask task(String name, long timestamp) {
        FeedTask row = new FeedTask();
        row.setTenantId(7L);
        row.setName(name);
        row.setAccountFilter("{}");
        row.setTitle("限时活动");
        row.setDescription("活动描述");
        row.setContent("活动正文");
        row.setPromotionLink("https://example.com");
        row.setTextColor("#FFFFFF");
        row.setBackgroundColor("#075E54");
        row.setConcurrency(3);
        row.setRetryMax(2);
        row.setStartMode("now");
        row.setTaskDelayMinutes(0);
        row.setTaskMode("instant");
        row.setStatus(1);
        row.setTaskStatus(0);
        row.setCurrentRoundNo(0L);
        row.setTotalAccountNum(0);
        row.setSuccessAccountNum(0);
        row.setFailedAccountNum(0);
        row.setCreatedAt(timestamp);
        row.setUpdatedAt(timestamp);
        return row;
    }

    private static FeedTaskAccount account(
            Long taskId, Long accountId, String phone, long timestamp) {
        FeedTaskAccount row = new FeedTaskAccount();
        row.setTenantId(7L);
        row.setTaskId(taskId);
        row.setAccountId(accountId);
        row.setAccountPhoneSnapshot(phone);
        row.setSendStatus("pending");
        row.setRetryNum(0);
        row.setRetryMax(2);
        row.setCreatedAt(timestamp);
        row.setUpdatedAt(timestamp);
        return row;
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE feed_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    name VARCHAR(128) NOT NULL,
                    account_filter VARCHAR(8192) NOT NULL,
                    title VARCHAR(256) NOT NULL,
                    description VARCHAR(512),
                    content VARCHAR(4096) NOT NULL,
                    promotion_link VARCHAR(2048) NOT NULL,
                    link_preview_image_file_id BIGINT,
                    text_color VARCHAR(16) NOT NULL,
                    background_color VARCHAR(16) NOT NULL,
                    concurrency INT NOT NULL,
                    retry_max INT NOT NULL,
                    start_mode VARCHAR(16) NOT NULL,
                    task_delay_minutes INT,
                    task_start_at BIGINT,
                    task_mode VARCHAR(16) NOT NULL,
                    task_planned_end_at BIGINT,
                    status TINYINT NOT NULL,
                    task_status TINYINT NOT NULL,
                    current_round_no BIGINT NOT NULL,
                    next_run_at BIGINT,
                    total_account_num INT NOT NULL,
                    success_account_num INT NOT NULL,
                    failed_account_num INT NOT NULL,
                    created_by BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE feed_task_account (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    task_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    account_phone_snapshot VARCHAR(64) NOT NULL,
                    send_status VARCHAR(16) NOT NULL,
                    retry_num INT NOT NULL,
                    retry_max INT NOT NULL,
                    command_id VARCHAR(128),
                    protocol_message_id VARCHAR(256),
                    send_at BIGINT,
                    success_at BIGINT,
                    failed_at BIGINT,
                    fail_code VARCHAR(64),
                    fail_reason VARCHAR(512),
                    round_no BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    UNIQUE (tenant_id, task_id, account_id)
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
            dataSource.setURL("jdbc:h2:mem:feed_task_mapper_test;MODE=MySQL;"
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
            factory.setMapperLocations(
                    new ClassPathResource("mapper/feed/FeedTaskMapper.xml"),
                    new ClassPathResource("mapper/feed/FeedTaskAccountMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        FeedTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(FeedTaskMapper.class);
        }

        @Bean
        FeedTaskAccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(FeedTaskAccountMapper.class);
        }
    }
}
