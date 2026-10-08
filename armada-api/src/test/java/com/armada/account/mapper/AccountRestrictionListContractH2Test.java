package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.account.controller.AccountController;
import com.armada.account.converter.AccountConverter;
import com.armada.account.service.AccountBatchLifecycleService;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountLifecycleCommandService;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.account.service.AccountWsPhoneExportService;
import com.armada.account.service.impl.AccountServiceImpl;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实列表 SQL 经 Service、MapStruct、Controller 到 JSON 的限制来源与租户隔离契约。 */
@SpringJUnitConfig(AccountRestrictionListContractH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountRestrictionListContractH2Test {

    @Autowired private DataSource dataSource;
    @Autowired private AccountMapper accountMapper;
    @Autowired private AccountGroupMapper accountGroupMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createSchema();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO account (id, tenant_id, created_at) VALUES (10, 1, 100), (20, 2, 200)");
            jdbc.update("INSERT INTO account_state (account_id, tenant_id) VALUES (10, 1), (20, 2)");
        });
        AccountServiceImpl service = new AccountServiceImpl(
                accountMapper, accountGroupMapper, Mappers.getMapper(AccountConverter.class));
        mvc = MockMvcBuilders.standaloneSetup(new AccountController(service,
                mock(AccountGroupService.class), mock(AccountOnlineCommandService.class),
                mock(AccountBatchLifecycleService.class), mock(AccountLifecycleCommandService.class),
                mock(AccountOnlineAttemptLogService.class), mock(AccountWsPhoneExportService.class),
                mock(AccountOperationRestrictionService.class))).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void activePlatformAndFallbackDeadlinesReachTheListJsonWithoutChangingExistingFields() throws Exception {
        jdbc.update("UPDATE account_state SET platform_message_restriction_active=1, "
                + "platform_message_restriction_until=30000, fallback_message_restriction_until=20000, "
                + "pulling_restriction_until=30000 WHERE tenant_id=1");

        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(10))
                .andExpect(jsonPath("$.data.list[0].platformMessageRestrictionUntil").value(30000))
                .andExpect(jsonPath("$.data.list[0].fallbackMessageRestrictionUntil").value(20000))
                .andExpect(jsonPath("$.data.list[0].messageRestrictionUntil").value(30000))
                .andExpect(jsonPath("$.data.list[0].pullingRestrictionUntil").value(30000));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0})
    void inactivePlatformDeadlineIsNullWhileFallbackRemainsVisible(Integer active) throws Exception {
        jdbc.update("UPDATE account_state SET platform_message_restriction_active=?, "
                + "platform_message_restriction_until=30000, fallback_message_restriction_until=20000, "
                + "pulling_restriction_until=40000 WHERE tenant_id=1", active);

        String body = mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].fallbackMessageRestrictionUntil").value(20000))
                .andExpect(jsonPath("$.data.list[0].messageRestrictionUntil").value(20000))
                .andExpect(jsonPath("$.data.list[0].pullingRestrictionUntil").value(40000))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"platformMessageRestrictionUntil\":null");
    }

    @Test
    void absentRestrictionsSerializeAsNullAndForeignTenantStateIsNotExposed() throws Exception {
        jdbc.update("UPDATE account_state SET platform_message_restriction_active=1, "
                + "platform_message_restriction_until=30000 WHERE tenant_id=2");

        String body = mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(10))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"platformMessageRestrictionUntil\":null",
                "\"fallbackMessageRestrictionUntil\":null",
                "\"messageRestrictionUntil\":null", "\"pullingRestrictionUntil\":null");
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT, ws_phone VARCHAR(32),
                  account_type TINYINT, declared_account_type TINYINT,
                  account_type_verify_status TINYINT, account_type_verify_source TINYINT,
                  account_type_verified_at BIGINT, business_verification_level TINYINT,
                  business_verification_source TINYINT, business_verification_verified_at BIGINT,
                  device_os TINYINT, number_source TINYINT, channel_name VARCHAR(128),
                  protocol_id VARCHAR(32), account_group_id BIGINT, ownership TINYINT,
                  lease_until BIGINT, dispatched_at BIGINT, created_at BIGINT, deleted_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE account_state (
                  account_id BIGINT, tenant_id BIGINT, account_state TINYINT, login_state TINYINT,
                  risk_status TINYINT, risk_end_time BIGINT, cooldown_until BIGINT, mute_status TINYINT,
                  fallback_message_restriction_until BIGINT, platform_message_restriction_until BIGINT,
                  platform_message_restriction_active TINYINT, pulling_restriction_until BIGINT,
                  restriction_reason_code VARCHAR(64), restriction_reported_at BIGINT,
                  block_error_code VARCHAR(32), block_reason VARCHAR(255), state_source VARCHAR(64),
                  truth_ip VARCHAR(45), proxy_country VARCHAR(64), proxy_source VARCHAR(64),
                  pull_into_group_count INT, invalidated_at BIGINT, last_state_sync_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE account_group (
                  id BIGINT, tenant_id BIGINT, name VARCHAR(100), marketing_occupancy_type VARCHAR(32),
                  marketing_occupancy_task_id BIGINT, marketing_locked_at BIGINT, deleted_at BIGINT)
                """);
        jdbc.execute("CREATE TABLE account_credential (account_id BIGINT, tenant_id BIGINT, "
                + "cred_format TINYINT, deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE ip_proxy (bound_account_id BIGINT, tenant_id BIGINT, "
                + "region VARCHAR(64), source VARCHAR(64), status TINYINT, deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE country (name_zh VARCHAR(64), flag VARCHAR(16), deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_account_group_binding (tenant_id BIGINT, account_id BIGINT, "
                + "participant_id BIGINT, group_id BIGINT)");
        jdbc.execute("CREATE TABLE wa_group_participant (id BIGINT, tenant_id BIGINT, "
                + "group_id BIGINT, presence_status TINYINT)");
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:restriction_list_contract;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource("mapper/account/AccountMapper.xml"),
                    new ClassPathResource("mapper/account/AccountGroupMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        AccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountMapper.class);
        }

        @Bean
        AccountGroupMapper accountGroupMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountGroupMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
