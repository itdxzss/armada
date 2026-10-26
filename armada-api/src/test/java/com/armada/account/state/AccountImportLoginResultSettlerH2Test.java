package com.armada.account.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 使用真实 Mapper 和租户插件验证导入结果结算，不回写已经冻结的历史结果。 */
class AccountImportLoginResultSettlerH2Test {

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private AccountImportLoginResultSettler settler;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:import_settle_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("""
                CREATE TABLE account_import_detail (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT,
                  parse_result TINYINT, online_phase TINYINT, login_result TINYINT,
                  online_dispatched_at BIGINT, login_settled_at BIGINT, login_reason VARCHAR(255)
                )
                """);
        jdbc.execute("""
                INSERT INTO account_import_detail VALUES
                  (1, 1, 100, 1, 2, NULL, 1000, NULL, NULL),
                  (2, 2, 100, 1, 2, NULL, 1000, NULL, NULL),
                  (3, 1, 100, 1, 3, 3, 1000, 1500, 'LOGIN_FAILED'),
                  (4, 1, 100, 1, 2, NULL, 4000, NULL, NULL),
                  (5, 1, 100, 1, 1, NULL, NULL, NULL, NULL),
                  (6, 1, 101, 1, 2, NULL, 1000, NULL, NULL)
                """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MyBatisConfig productionConfig = new MyBatisConfig();
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(productionConfig.mybatisPlusInterceptor(productionConfig.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/account/AccountImportDetailMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
        settler = new AccountImportLoginResultSettler(sessions.getMapper(AccountImportDetailMapper.class));
        TenantContext.set(1L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "OFFLINE, CLIENT_OUTDATED, 405, 2, CLIENT_OUTDATED",
            "NEED_REAUTH, LOGIN_FAILED, 405, 2, CLIENT_OUTDATED",
            "NEED_REAUTH, LOGIN_FAILED, 401, 3, LOGIN_FAILED",
            "NEED_REAUTH, LOGIN_FAILED, 403, 4, FORBIDDEN"
    })
    void settlesOnlyCurrentDispatchedDetail(String to, String semantic, int rawCode,
                                           int expectedResult, String expectedReason) {
        Account account = new Account();
        account.setId(100L);
        AccountStateChangedEvent event = new AccountStateChangedEvent(1L, 100L,
                "acc_8613800138000", "VERIFYING", to, 2_000L, semantic, rawCode,
                "import", "attempt-405", null);

        transaction.executeWithoutResult(status -> settler.afterStateChanged(account, event, 2_000L));
        transaction.executeWithoutResult(status -> settler.afterStateChanged(account, event, 3_000L));

        assertThat(jdbc.queryForObject("SELECT login_result FROM account_import_detail WHERE id=1", Integer.class))
                .isEqualTo(expectedResult);
        assertThat(jdbc.queryForObject("SELECT login_reason FROM account_import_detail WHERE id=1", String.class))
                .isEqualTo(expectedReason);
        assertThat(jdbc.queryForObject("SELECT login_settled_at FROM account_import_detail WHERE id=1", Long.class))
                .isEqualTo(2_000L);
        assertThat(jdbc.queryForObject("SELECT online_phase FROM account_import_detail WHERE id=1", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT login_result FROM account_import_detail WHERE id=3", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT login_reason FROM account_import_detail WHERE id=3", String.class))
                .isEqualTo("LOGIN_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_import_detail WHERE login_result IS NULL",
                Integer.class)).isEqualTo(4);
    }
}
