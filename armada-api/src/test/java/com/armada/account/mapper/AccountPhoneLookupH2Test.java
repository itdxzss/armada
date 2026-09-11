package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import com.armada.account.service.impl.AccountServiceImpl;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 真实账号查询与租户拦截器共同约束详情中的手机号展示。 */
@SpringJUnitConfig(AccountPhoneLookupH2Test.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class AccountPhoneLookupH2Test {
    @org.springframework.beans.factory.annotation.Autowired private DataSource dataSource;
    @org.springframework.beans.factory.annotation.Autowired private AccountMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        execute("DROP ALL OBJECTS");
        execute("CREATE TABLE account (id BIGINT PRIMARY KEY, tenant_id BIGINT, ws_phone VARCHAR(32), deleted_at BIGINT)");
        execute("INSERT INTO account VALUES (685,7,'15550000685',NULL),(690,7,'15550000690',NULL),"
                + "(700,8,'15550000700',NULL),(701,7,'15550000701',1),(702,7,' ',NULL)");
    }
    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test
    void returnsOnlyRequestedActiveTenantPhonesIncludingAccountsWithoutProtocolState() {
        var service = new AccountServiceImpl(mapper, null, null);
        assertThat(service.getPhonesByIds(List.of(685L,685L,690L,700L,701L,702L,999L)))
                .isEqualTo(Map.of(685L,"15550000685",690L,"15550000690"));
        TenantContext.set(8L);
        assertThat(service.getPhonesByIds(List.of(685L,700L)))
                .isEqualTo(Map.of(700L,"15550000700"));
    }
    @Test
    void emptySelectionReturnsNoPhones() {
        var service = new AccountServiceImpl(mapper, null, null);
        assertThat(service.getPhonesByIds(List.of())).isEmpty();
        assertThat(service.getPhonesByIds(null)).isEmpty();
    }
    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:account_phone_lookup;MODE=MySQL;"
                    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource("mapper/account/AccountMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        AccountMapper mapper(SqlSessionTemplate template) {
            return template.getMapper(AccountMapper.class);
        }
    }
}
